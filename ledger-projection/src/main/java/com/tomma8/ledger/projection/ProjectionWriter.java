package com.tomma8.ledger.projection;

import com.tomma8.ledger.dao.mapper.AccountBalanceMapper;
import com.tomma8.ledger.dao.mapper.AccountMapper;
import com.tomma8.ledger.dao.mapper.JournalMapper;
import com.tomma8.ledger.utils.SnowflakeIdGenerator;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Owns all MySQL persistence for the projection service: surrogate-PK caches,
 * the batched journal/journal_line/event_log writes, and synchronous per-poll
 * balance upserts. {@link ProjectionConsumer} only parses Kafka messages and
 * delegates here.
 */
@Service
public class ProjectionWriter {

    private static final Logger log = LoggerFactory.getLogger(ProjectionWriter.class);

    private final SqlSessionFactory sqlSessionFactory;
    private final SnowflakeIdGenerator idGenerator;

    // accountId → surrogate PK
    private final ConcurrentHashMap<String, Long> accountIdCache = new ConcurrentHashMap<>();
    // journalId → surrogate PK (INSERT IGNORE makes this optional but saves a SELECT)
    private final ConcurrentHashMap<String, Long> journalPkCache = new ConcurrentHashMap<>();

    private final JournalFlushBuffer journalFlushBuffer;

    private final AtomicLong lastEventTimestamp = new AtomicLong(System.currentTimeMillis());
    private final LongAdder eventsProcessed = new LongAdder();
    private final LongAdder balanceWrites = new LongAdder();

    public ProjectionWriter(SqlSessionFactory sqlSessionFactory,
                            MeterRegistry meterRegistry,
                            @Value("${ledger.projection.journal.flush-interval-ms:50}") int journalFlushIntervalMs,
                            @Value("${ledger.projection.journal.max-buffer:4000}") int journalMaxBuffer) {
        this.sqlSessionFactory = sqlSessionFactory;
        this.idGenerator = SnowflakeIdGenerator.forWorker(SnowflakeIdGenerator.deriveWorkerId());

        this.journalFlushBuffer = new JournalFlushBuffer(
                sqlSessionFactory, meterRegistry, journalFlushIntervalMs, journalMaxBuffer,
                count -> eventsProcessed.add(count));

        Gauge.builder("ledger.projection.seconds.since.last.event", this,
                pw -> (System.currentTimeMillis() - pw.lastEventTimestamp.get()) / 1000.0)
                .description("Seconds since last projection event was processed.")
                .baseUnit("seconds").register(meterRegistry);

        Gauge.builder("ledger.projection.events.processed", eventsProcessed, LongAdder::doubleValue)
                .description("Total projection events processed.").register(meterRegistry);

        Gauge.builder("ledger.projection.balance.writes", balanceWrites, LongAdder::doubleValue)
                .description("Total balance SQL writes after in-batch conflation.").register(meterRegistry);
    }

    // ============================================================
    // Account events
    // ============================================================

    public void writeAccount(String accountId, String accountType, String displayName,
                             String ownerId, String status, LocalDateTime createdAt) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            AccountMapper am = session.getMapper(AccountMapper.class);
            am.upsertAccount(accountId, accountType, displayName, ownerId, status, createdAt);
            session.commit();
            Long pk = am.findIdByAccountId(accountId);
            if (pk != null) accountIdCache.put(accountId, pk);
        }
        lastEventTimestamp.set(System.currentTimeMillis());
        eventsProcessed.increment();
    }

    // ============================================================
    // Balance change events — one whole poll batch per call
    // ============================================================

    /**
     * Persist a whole poll batch. PKs are resolved per-batch, then:
     * <ol>
     *   <li>balance rows are conflated in-memory (highest {@code accountSeq}
     *       per account/balanceType/currency) and upserted synchronously in one
     *       JDBC batch — durable before this method returns;</li>
     *   <li>journal_line + projection_event_log rows are written via
     *       {@link JournalFlushBuffer#flushPollBatch} — per-poll, per-shard
     *       multi-row INSERT in parallel, without the shared async queue;</li>
     * </ol>
     *
     * <p><b>Throws</b> on any error. The caller must NOT acknowledge the offset
     * — the broker will redeliver the same poll batch (idempotent via
     * {@code INSERT IGNORE} / accountSeq-guarded upsert).
     */
    public void writeBalanceBatch(List<BalanceEvent> parsed) throws InterruptedException {
        if (parsed == null || parsed.isEmpty()) return;

        resolveAccountPks(parsed);
        resolveJournalPks(parsed);

        LocalDateTime now = LocalDateTime.now();
        var conflated = new LinkedHashMap<BalanceUpdate, BalanceUpdate>();
        var journalRows = new ArrayList<JournalFlushBuffer.PendingRow>(parsed.size());

        for (BalanceEvent pe : parsed) {
            Long accountPk = accountIdCache.get(pe.accountId());
            Long journalPk = journalPkCache.get(pe.journalId());
            if (accountPk == null || journalPk == null) {
                throw new IllegalStateException("Missing surrogate PK for account=" + pe.accountId()
                        + " or journal=" + pe.journalId()
                        + " after resolve phase — cannot persist batch");
            }

            var bu = new BalanceUpdate(accountPk, pe.accountId(), pe.balanceType(), pe.currency(),
                    pe.postBalance(), pe.position(), pe.accountSeq(), pe.journalId());
            BalanceUpdate prev = conflated.get(bu);
            if (prev == null || bu.accountSeq() >= prev.accountSeq()) {
                conflated.put(bu, bu);
            }

            journalRows.add(new JournalFlushBuffer.PendingRow(
                    ShardRouting.shardIndex(pe.accountId()),
                    idGenerator.nextId(), journalPk, accountPk, pe, now));
        }

        upsertBalancesSync(conflated);
        journalFlushBuffer.flushPollBatch(journalRows);

        lastEventTimestamp.set(System.currentTimeMillis());
    }

    /** Synchronous JDBC batch upsert; throws on SQL failure (no silent loss). */
    private void upsertBalancesSync(LinkedHashMap<BalanceUpdate, BalanceUpdate> conflated) {
        if (conflated.isEmpty()) return;
        try (SqlSession session = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
            AccountBalanceMapper bm = session.getMapper(AccountBalanceMapper.class);
            for (BalanceUpdate bu : conflated.values()) {
                bm.upsertBalance(bu.accountPk(), bu.accountId(), bu.balanceType(), bu.currency(),
                        bu.amount(), bu.position(), bu.accountSeq(), bu.lastJournalId());
            }
            session.flushStatements();
            session.commit();
        }
        balanceWrites.add(conflated.size());
    }

    /** Resolve account surrogate PKs for the batch; only cache misses hit the DB, in one session. */
    private void resolveAccountPks(List<BalanceEvent> parsed) {
        LinkedHashSet<String> missing = null;
        for (BalanceEvent pe : parsed) {
            if (!accountIdCache.containsKey(pe.accountId())) {
                if (missing == null) missing = new LinkedHashSet<>();
                missing.add(pe.accountId());
            }
        }
        if (missing == null) return;
        try (SqlSession session = sqlSessionFactory.openSession()) {
            AccountMapper am = session.getMapper(AccountMapper.class);
            for (String accountId : missing) {
                Long pk = am.findIdByAccountId(accountId);
                if (pk == null) {
                    am.upsertAccount(accountId, "CLIENT", "", null, "ACTIVE", LocalDateTime.now());
                    pk = am.findIdByAccountId(accountId);
                }
                if (pk != null) accountIdCache.put(accountId, pk);
                else log.error("Failed to get account PK for {}", accountId);
            }
            session.commit();
        }
    }

    /**
     * Resolve journal surrogate PKs for the batch; only cache misses hit the DB,
     * in one session. INSERT IGNORE + SELECT yields a stable PK whether the
     * journal was newly inserted or already existed.
     */
    private void resolveJournalPks(List<BalanceEvent> parsed) {
        LinkedHashMap<String, BalanceEvent> missing = null;
        for (BalanceEvent pe : parsed) {
            if (!journalPkCache.containsKey(pe.journalId())) {
                if (missing == null) missing = new LinkedHashMap<>();
                missing.putIfAbsent(pe.journalId(), pe);
            }
        }
        if (missing == null) return;
        try (SqlSession session = sqlSessionFactory.openSession()) {
            JournalMapper jm = session.getMapper(JournalMapper.class);
            for (var entry : missing.entrySet()) {
                String journalId = entry.getKey();
                BalanceEvent pe = entry.getValue();
                long journalPk = idGenerator.nextId();
                jm.insertJournal(journalPk, journalId,
                        "REVERSAL".equals(pe.commandType()) ? "REVERSAL" : "NORMAL",
                        pe.requestId(), pe.commandType(), pe.businessRef(),
                        pe.valueDate(), "CONFIRMED", false, LocalDateTime.now());
                Long actual = jm.findIdByJournalId(journalId);
                journalPkCache.put(journalId, actual != null ? actual : journalPk);
            }
            session.commit();
        }
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        journalFlushBuffer.close();
    }

    /** Parsed balance-change event passed from {@link ProjectionConsumer}. */
    public record BalanceEvent(
            String eventId, String journalId, String journalLineId, String requestId,
            String commandType, String accountId, String balanceType, String position,
            String currency, String entryType, BigDecimal amount, BigDecimal postBalance,
            long accountSeq, String businessRef, LocalDate valueDate, int configVersion,
            BigDecimal preBalance) {}
}

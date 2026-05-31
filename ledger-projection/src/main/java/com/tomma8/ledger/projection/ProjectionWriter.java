package com.tomma8.ledger.projection;

import com.tomma8.ledger.dao.mapper.AccountBalanceMapper;
import com.tomma8.ledger.dao.mapper.AccountMapper;
import com.tomma8.ledger.dao.mapper.JournalMapper;
import com.tomma8.ledger.dao.mapper.ProjectionEventLogMapper;
import com.tomma8.ledger.utils.SnowflakeIdGenerator;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * the batched journal/journal_line/event_log writes, and the asynchronous
 * conflated balance upserts. {@link ProjectionConsumer} only parses Kafka
 * messages and delegates here.
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

    private final ConflationQueue balanceQueue = new ConflationQueue();
    private final Thread balanceWorker;

    private final AtomicLong lastEventTimestamp = new AtomicLong(System.currentTimeMillis());
    private final LongAdder eventsProcessed = new LongAdder();
    private final LongAdder balanceWrites = new LongAdder();

    public ProjectionWriter(SqlSessionFactory sqlSessionFactory, MeterRegistry meterRegistry) {
        this.sqlSessionFactory = sqlSessionFactory;
        this.idGenerator = SnowflakeIdGenerator.forWorker(SnowflakeIdGenerator.deriveWorkerId());
        this.balanceWorker = Thread.ofPlatform().daemon()
                .name("projection-balance-worker")
                .start(this::balanceWorkerLoop);

        Gauge.builder("ledger.projection.seconds.since.last.event", this,
                pw -> (System.currentTimeMillis() - pw.lastEventTimestamp.get()) / 1000.0)
                .description("Seconds since last projection event was processed.")
                .baseUnit("seconds").register(meterRegistry);

        Gauge.builder("ledger.projection.events.processed", eventsProcessed, LongAdder::doubleValue)
                .description("Total projection events processed.").register(meterRegistry);

        Gauge.builder("ledger.projection.balance.queue.depth", balanceQueue, ConflationQueue::size)
                .description("Pending conflated balance updates.").register(meterRegistry);

        Gauge.builder("ledger.projection.balance.writes", balanceWrites, LongAdder::doubleValue)
                .description("Total balance SQL writes after conflation.").register(meterRegistry);
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
     * Persist a whole poll batch. PKs are resolved per-batch (cache-first, only
     * misses hit the DB), then all journal_line + event_log rows are written on
     * a single connection committed once. Balance upserts are handed to the
     * conflation queue and applied asynchronously off the consumer thread.
     */
    public void writeBalanceBatch(List<BalanceEvent> parsed) {
        if (parsed == null || parsed.isEmpty()) return;

        try {
            resolveAccountPks(parsed);
            resolveJournalPks(parsed);

            // One SIMPLE session for the whole poll. We do NOT use
            // ExecutorType.BATCH: ShardingSphere's SINGLE-table route
            // (projection_event_log) throws TableNotFound under the batch
            // prepared-statement executor. This still collapses the old
            // per-message session/commit churn into one session + one commit.
            LocalDateTime now = LocalDateTime.now();
            try (SqlSession session = sqlSessionFactory.openSession()) {
                JournalMapper jm = session.getMapper(JournalMapper.class);
                ProjectionEventLogMapper em = session.getMapper(ProjectionEventLogMapper.class);
                for (BalanceEvent pe : parsed) {
                    Long accountPk = accountIdCache.get(pe.accountId());
                    Long journalPk = journalPkCache.get(pe.journalId());
                    if (accountPk == null || journalPk == null) continue;
                    jm.insertJournalLine(idGenerator.nextId(), journalPk, accountPk, 0L,
                            pe.journalLineId(), pe.journalId(), pe.accountId(), "",
                            pe.balanceType(), pe.position(), pe.currency(), pe.entryType(),
                            pe.amount(), pe.preBalance(), pe.postBalance(), pe.configVersion(), now);
                    em.insertEvent(pe.accountId(), pe.balanceType(), pe.currency(), pe.accountSeq(),
                            pe.journalLineId(), pe.journalId(), pe.eventId(), "APPLIED");
                }
                session.commit();
            }

            for (BalanceEvent pe : parsed) {
                Long accountPk = accountIdCache.get(pe.accountId());
                if (accountPk == null) continue;
                balanceQueue.offer(new BalanceUpdate(accountPk, pe.accountId(), pe.balanceType(),
                        pe.currency(), pe.postBalance(), pe.position(), pe.accountSeq(), pe.journalId()));
            }

            lastEventTimestamp.set(System.currentTimeMillis());
            eventsProcessed.add(parsed.size());

        } catch (Exception e) {
            log.error("Failed to project balance batch of {} events", parsed.size(), e);
        }
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

    // ============================================================
    // Balance worker — drains conflation queue, batch upserts MySQL
    // ============================================================

    private void balanceWorkerLoop() {
        log.info("Balance worker started");
        var batch = new ArrayList<BalanceUpdate>(200);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (balanceQueue.isEmpty()) {
                    Thread.sleep(5);
                    continue;
                }
                batch.clear();
                int drained = balanceQueue.drainTo(batch, 200);
                if (drained == 0) continue;

                try (SqlSession session = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
                    AccountBalanceMapper bm = session.getMapper(AccountBalanceMapper.class);
                    for (var bu : batch) {
                        bm.upsertBalance(bu.accountPk(), bu.accountId(), bu.balanceType(), bu.currency(),
                                bu.amount(), bu.position(), bu.accountSeq(), bu.lastJournalId());
                    }
                    session.flushStatements();
                    session.commit();
                }
                balanceWrites.add(drained);
                log.info("[BALANCE] drained={} queueSize={} offered={} conflated={}",
                        drained, balanceQueue.size(),
                        balanceQueue.offeredCount(), balanceQueue.conflatedCount());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Balance worker error", e);
            }
        }
        log.info("Balance worker stopped");
    }

    /** Parsed balance-change event passed from {@link ProjectionConsumer}. */
    public record BalanceEvent(
            String eventId, String journalId, String journalLineId, String requestId,
            String commandType, String accountId, String balanceType, String position,
            String currency, String entryType, BigDecimal amount, BigDecimal postBalance,
            long accountSeq, String businessRef, LocalDate valueDate, int configVersion,
            BigDecimal preBalance) {}
}

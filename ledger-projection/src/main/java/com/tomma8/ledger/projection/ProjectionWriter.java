package com.tomma8.ledger.projection;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.tomma8.ledger.dao.mapper.AccountMapper;
import com.tomma8.ledger.dao.mapper.JournalMapper;
import com.tomma8.ledger.utils.SnowflakeIdGenerator;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.tomma8.ledger.projection.config.ProjectionLedgerProperties;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
    private final BalanceUpsertExecutor balanceUpsertExecutor;

    // accountId → surrogate PK. Unbounded is fine: cardinality = #accounts (small, stable).
    private final ConcurrentHashMap<String, Long> accountIdCache = new ConcurrentHashMap<>();
    // journalId → surrogate PK. Journal cardinality grows with EVERY posting, so an unbounded
    // map is a slow heap leak (millions of entries on a long run) and old-gen GC pressure.
    // Bounded Caffeine (asMap view keeps ConcurrentMap call sites): a journal's events arrive in
    // the same or adjacent polls, so 100k entries is minutes of headroom; on the rare eviction
    // resolveJournalPks simply re-resolves (INSERT ON CONFLICT DO NOTHING + SELECT).
    private final ConcurrentMap<String, Long> journalPkCache =
            Caffeine.newBuilder().maximumSize(100_000).<String, Long>build().asMap();
    // Max rows per journal-header multi-row INSERT — bounds <foreach> SQL shapes (parse cache).
    private static final int JOURNAL_INSERT_CHUNK = 128;
    // Hoisted per Effective Java Item 6 — was rebuilt (3 comparators + method refs) per poll batch.
    private static final Comparator<BalanceUpdate> BALANCE_UPSERT_ORDER =
            Comparator.comparing(BalanceUpdate::accountId)
                    .thenComparing(BalanceUpdate::balanceType)
                    .thenComparing(BalanceUpdate::currency);

    private final JournalFlushBuffer journalFlushBuffer;

    private final AtomicLong lastEventTimestamp = new AtomicLong(System.currentTimeMillis());
    private final LongAdder eventsProcessed = new LongAdder();
    private final LongAdder balanceWrites = new LongAdder();

    // Thread-local scratch — one ArrayList<PendingRow> per Kafka consumer thread,
    // reused across poll batches. The caller (ProjectionConsumer.onBalanceChange)
    // passes it through flushPollBatch synchronously and never re-reads after
    // that method returns, so reusing the same backing array is safe.
    private static final ThreadLocal<ArrayList<JournalFlushBuffer.PendingRow>> JOURNAL_ROW_SCRATCH =
            ThreadLocal.withInitial(() -> new ArrayList<>(1024));

    // Per-poll container scratch (Layer 2 reuse): writeBalanceBatch + resolveJournalPks run
    // serially on one Kafka consumer thread, fully consuming these before the next poll. Clear at
    // entry, never retain refs across polls — backing arrays stay warm on the thread's young gen.
    private static final ThreadLocal<LinkedHashMap<BalanceUpdate, BalanceUpdate>> CONFLATED_SCRATCH =
            ThreadLocal.withInitial(() -> new LinkedHashMap<>(1024));
    private static final ThreadLocal<HashMap<String, Long>> JOURNAL_PK_BATCH_SCRATCH =
            ThreadLocal.withInitial(() -> new HashMap<>(1024));
    private static final ThreadLocal<LinkedHashMap<String, ProjectionWriter.BalanceEvent>> MISSING_JOURNAL_SCRATCH =
            ThreadLocal.withInitial(() -> new LinkedHashMap<>(256));
    private static final ThreadLocal<ArrayList<JournalMapper.JournalBatchRow>> JOURNAL_BATCH_ROW_SCRATCH =
            ThreadLocal.withInitial(() -> new ArrayList<>(256));

    public ProjectionWriter(SqlSessionFactory sqlSessionFactory,
                            MeterRegistry meterRegistry,
                            ProjectionLedgerProperties ledgerProps) {
        this.sqlSessionFactory = sqlSessionFactory;
        var journal = ledgerProps.getProjection().getJournal();
        var balanceAsync = ledgerProps.getProjection().getBalance().getAsync();
        String snowflakeWorkerIdRaw = ledgerProps.getSnowflake().getWorkerId();
        long workerId = snowflakeWorkerIdRaw.isBlank()
                ? SnowflakeIdGenerator.deriveWorkerId(snowflakeWorkerIdRaw)
                : Long.parseLong(snowflakeWorkerIdRaw.trim());
        this.idGenerator = SnowflakeIdGenerator.forWorker(workerId);

        this.balanceUpsertExecutor = new BalanceUpsertExecutor(
                sqlSessionFactory, meterRegistry,
                balanceAsync.getQueueCapacity(), balanceAsync.getSubmitTimeoutMs());

        this.journalFlushBuffer = new JournalFlushBuffer(
                sqlSessionFactory, meterRegistry,
                journal.getFlushIntervalMs(), journal.getMaxBuffer(),
                journal.isEventLogEnabled(),
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
    public CompletableFuture<Void> writeBalanceBatch(List<BalanceEvent> parsed) throws InterruptedException {
        if (parsed == null || parsed.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        resolveAccountPks(parsed);
        // Authoritative for THIS batch. journalPkCache is a bounded Caffeine accelerator whose
        // TinyLFU admission may evict a freshly-put entry immediately once the cache is full —
        // "put then get" is NOT guaranteed to hit. Correctness must never depend on cache policy,
        // so resolveJournalPks returns every resolved PK in a plain local map.
        Map<String, Long> batchJournalPks = resolveJournalPks(parsed);

        LocalDateTime now = LocalDateTime.now();
        var conflated = CONFLATED_SCRATCH.get();
        conflated.clear();
        ArrayList<JournalFlushBuffer.PendingRow> journalRows = JOURNAL_ROW_SCRATCH.get();
        journalRows.clear();

        for (BalanceEvent pe : parsed) {
            Long accountPk = accountIdCache.get(pe.accountId());
            Long journalPk = batchJournalPks.get(pe.journalId());
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

        CompletableFuture<Void> balanceFuture = submitBalanceUpsert(conflated);
        journalFlushBuffer.flushPollBatch(journalRows);
        journalRows.clear();   // release refs for next poll batch on the same thread
        conflated.clear();     // release the BalanceUpdate keys/values (DTOs) for next poll

        lastEventTimestamp.set(System.currentTimeMillis());
        return balanceFuture;
    }

    /** Submit the sorted balance batch to the async executor; returns the
     * completion future. Empty input → already-complete future.
     * Balance updates are sorted by (accountId, balanceType, currency) so
     * concurrent transactions acquire account_balance unique-key locks in
     * a consistent order across projection instances, preventing deadlocks. */
    private CompletableFuture<Void> submitBalanceUpsert(LinkedHashMap<BalanceUpdate, BalanceUpdate> conflated) {
        if (conflated.isEmpty()) return CompletableFuture.completedFuture(null);
        var sorted = new ArrayList<>(conflated.values());
        sorted.sort(BALANCE_UPSERT_ORDER);
        CompletableFuture<Void> f = balanceUpsertExecutor.submit(sorted);
        balanceWrites.add(conflated.size());
        return f;
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
     * in one session. Returns a plain per-batch map holding EVERY journalId in
     * {@code parsed} — the caller must read PKs from this map, never back out of
     * {@code journalPkCache}: the bounded cache's TinyLFU admission may evict a
     * freshly-put entry at once when full, so put-then-get is not guaranteed to
     * hit. The cache is only a cross-batch accelerator.
     */
    private Map<String, Long> resolveJournalPks(List<BalanceEvent> parsed) {
        HashMap<String, Long> batch = JOURNAL_PK_BATCH_SCRATCH.get();
        batch.clear();
        LinkedHashMap<String, BalanceEvent> missing = MISSING_JOURNAL_SCRATCH.get();
        missing.clear();
        for (BalanceEvent pe : parsed) {
            String journalId = pe.journalId();
            if (batch.containsKey(journalId)) continue;
            Long cached = journalPkCache.get(journalId);
            if (cached != null) {
                batch.put(journalId, cached);
            } else {
                missing.putIfAbsent(journalId, pe);
            }
        }
        if (missing.isEmpty()) return batch;
        // Multi-row chunked insert replaces the old per-journal INSERT(+SELECT) loop — the poll
        // cycle's dominant serial cost (~1 statement per journal, all round-trips). Chunks of
        // ≤JOURNAL_INSERT_CHUNK keep the <foreach> SQL shape space bounded (parse-cache friendly).
        LocalDateTime now = LocalDateTime.now();
        ArrayList<JournalMapper.JournalBatchRow> rows = JOURNAL_BATCH_ROW_SCRATCH.get();
        rows.clear();
        rows.ensureCapacity(missing.size());
        for (var entry : missing.entrySet()) {
            BalanceEvent pe = entry.getValue();
            long journalPk = idGenerator.nextId();
            rows.add(new JournalMapper.JournalBatchRow(
                    journalPk, entry.getKey(),
                    "REVERSAL".equals(pe.commandType()) ? "REVERSAL" : "NORMAL",
                    pe.requestId(), pe.commandType(), pe.businessRef(),
                    pe.valueDate(), "CONFIRMED", false, now));
            batch.put(entry.getKey(), journalPk);   // optimistic: our generated PK
        }
        try (SqlSession session = sqlSessionFactory.openSession()) {
            JournalMapper jm = session.getMapper(JournalMapper.class);
            ArrayList<String> conflicted = null;
            for (int from = 0; from < rows.size(); from += JOURNAL_INSERT_CHUNK) {
                List<JournalMapper.JournalBatchRow> chunk =
                        rows.subList(from, Math.min(from + JOURNAL_INSERT_CHUNK, rows.size()));
                int affected = jm.batchInsertJournals(chunk);
                if (affected != chunk.size()) {
                    // Some rows lost an ON CONFLICT race (redelivery / concurrent instance): the
                    // pre-existing PKs differ from our optimistic ones. Collect the chunk's ids
                    // for a batch lookup; overwriting all of them is correct either way.
                    if (conflicted == null) conflicted = new ArrayList<>();
                    for (JournalMapper.JournalBatchRow r : chunk) conflicted.add(r.journalId());
                }
            }
            if (conflicted != null) {
                for (int from = 0; from < conflicted.size(); from += JOURNAL_INSERT_CHUNK) {
                    List<String> idChunk =
                            conflicted.subList(from, Math.min(from + JOURNAL_INSERT_CHUNK, conflicted.size()));
                    for (Map<String, Object> row : jm.findIdsByJournalIds(idChunk)) {
                        batch.put((String) row.get("journal_id"), ((Number) row.get("id")).longValue());
                    }
                }
            }
            session.commit();
        }
        // Refresh the cross-batch accelerator AFTER conflict correction (best-effort only).
        for (String journalId : missing.keySet()) {
            journalPkCache.put(journalId, batch.get(journalId));
        }
        return batch;
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

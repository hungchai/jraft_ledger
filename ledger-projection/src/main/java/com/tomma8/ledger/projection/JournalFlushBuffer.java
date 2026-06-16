package com.tomma8.ledger.projection;

import com.tomma8.ledger.dao.mapper.JournalMapper;
import com.tomma8.ledger.dao.mapper.ProjectionEventLogMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongConsumer;

/**
 * Micro-batch writer for sharded journal_line + projection_event_log rows.
 * Kafka consumer threads enqueue; one scheduled flusher per shard drains
 * its queue every {@code flushIntervalMs} (or when the queue exceeds
 * {@code maxBuffer}) and issues one multi-row INSERT per shard.
 */
public class JournalFlushBuffer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JournalFlushBuffer.class);

    private static final int DEADLOCK_MAX_RETRIES = 3;
    private static final long DEADLOCK_RETRY_BASE_MS = 20;

    private final SqlSessionFactory sqlSessionFactory;
    private final int flushIntervalMs;
    private final int maxBuffer;
    private final LongConsumer onFlushedEventCount;

    private final List<ConcurrentLinkedQueue<PendingRow>> shardQueues;
    private final List<ScheduledExecutorService> flushers;
    private final LongAdder flushBatches = new LongAdder();
    private final LongAdder flushRows = new LongAdder();
    private final AtomicLong totalBuffered = new AtomicLong();

    public JournalFlushBuffer(SqlSessionFactory sqlSessionFactory,
                              MeterRegistry meterRegistry,
                              int flushIntervalMs,
                              int maxBuffer,
                              LongConsumer onFlushedEventCount) {
        this.sqlSessionFactory = sqlSessionFactory;
        this.flushIntervalMs = Math.max(1, flushIntervalMs);
        this.maxBuffer = Math.max(64, maxBuffer);
        this.onFlushedEventCount = onFlushedEventCount;

        this.shardQueues = new ArrayList<>(ShardRouting.SHARD_COUNT);
        this.flushers = new ArrayList<>(ShardRouting.SHARD_COUNT);
        for (int shard = 0; shard < ShardRouting.SHARD_COUNT; shard++) {
            shardQueues.add(new ConcurrentLinkedQueue<>());
            int capturedShard = shard;
            ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "projection-journal-flusher-" + capturedShard);
                t.setDaemon(true);
                return t;
            });
            exec.scheduleAtFixedRate(() -> flushShardSafely(capturedShard), flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
            flushers.add(exec);
        }

        Gauge.builder("ledger.projection.journal.buffer.depth", totalBuffered, AtomicLong::doubleValue)
                .description("Pending journal_line/event_log rows awaiting micro-batch flush (all shards).")
                .register(meterRegistry);
        Gauge.builder("ledger.projection.journal.flush.batches", flushBatches, LongAdder::doubleValue)
                .description("Total journal micro-batch flush cycles.")
                .register(meterRegistry);
        Gauge.builder("ledger.projection.journal.flush.rows", flushRows, LongAdder::doubleValue)
                .description("Total journal_line rows flushed via micro-batch.")
                .register(meterRegistry);

        log.info("JournalFlushBuffer started: flushIntervalMs={} maxBuffer={} shards={}",
                this.flushIntervalMs, this.maxBuffer, ShardRouting.SHARD_COUNT);
    }

    /**
     * Enqueue one resolved row. Routes to the per-shard queue and may trigger
     * an early flush. Does NOT wait for the SQL write to complete; the caller
     * is expected to invoke {@link #flushAll()} (or rely on the scheduled
     * flusher) before acknowledging its Kafka poll batch.
     */
    public void offer(PendingRow row) {
        int shard = row.shardIndex();
        if (shard < 0 || shard >= shardQueues.size()) shard = 0;
        shardQueues.get(shard).offer(row);
        long total = totalBuffered.incrementAndGet();
        if (total >= maxBuffer) {
            final int targetShard = shard;
            flushers.get(targetShard).execute(() -> flushShardSafely(targetShard));
        }
    }

    private void flushShardSafely(int shard) {
        try {
            flushShard(shard);
        } catch (Exception e) {
            log.error("Journal micro-batch flush failed (shard={})", shard, e);
        }
    }

    /**
     * Synchronously write one Kafka poll batch: group rows by shard and issue
     * one multi-row INSERT per non-empty shard in parallel. Does not touch the
     * shared async queues — avoids cross-consumer interference on the hot path.
     *
     * <p>Re-throws on failure so the listener can refuse to ack; rows remain
     * only in the caller's memory and Kafka will redeliver the poll batch.
     */
    public void flushPollBatch(List<PendingRow> rows) throws InterruptedException {
        if (rows == null || rows.isEmpty()) return;

        @SuppressWarnings("unchecked")
        var byShard = (List<PendingRow>[]) new List[ShardRouting.SHARD_COUNT];
        for (int i = 0; i < ShardRouting.SHARD_COUNT; i++) {
            byShard[i] = new ArrayList<>();
        }
        for (PendingRow row : rows) {
            int shard = row.shardIndex();
            if (shard < 0 || shard >= ShardRouting.SHARD_COUNT) shard = 0;
            byShard[shard].add(row);
        }

        var latch = new CountDownLatch(ShardRouting.SHARD_COUNT);
        var firstError = new AtomicReference<Throwable>();
        for (int shard = 0; shard < ShardRouting.SHARD_COUNT; shard++) {
            List<PendingRow> batch = byShard[shard];
            if (batch.isEmpty()) {
                latch.countDown();
                continue;
            }
            Thread.startVirtualThread(() -> {
                try {
                    flushRowsDirect(batch);
                } catch (Throwable t) {
                    firstError.compareAndSet(null, t);
                } finally {
                    latch.countDown();
                }
            });
        }
        if (!latch.await(30, TimeUnit.SECONDS)) {
            throw new RuntimeException("JournalFlushBuffer.flushPollBatch timed out after 30s");
        }
        propagateFlushError(firstError.get());
    }

    /**
     * Synchronously flush every shard queue. Used on shutdown and as a fallback
     * for any rows still in the async buffers.
     */
    public void flushAll() throws InterruptedException {
        var latch = new CountDownLatch(ShardRouting.SHARD_COUNT);
        var firstError = new AtomicReference<Throwable>();
        for (int shard = 0; shard < ShardRouting.SHARD_COUNT; shard++) {
            final int targetShard = shard;
            flushers.get(targetShard).execute(() -> {
                try {
                    flushShard(targetShard);
                } catch (Throwable t) {
                    firstError.compareAndSet(null, t);
                } finally {
                    latch.countDown();
                }
            });
        }
        if (!latch.await(30, TimeUnit.SECONDS)) {
            throw new RuntimeException("JournalFlushBuffer.flushAll timed out after 30s");
        }
        propagateFlushError(firstError.get());
    }

    private static void propagateFlushError(Throwable err) {
        if (err == null) return;
        if (err instanceof RuntimeException re) throw re;
        if (err instanceof Error e) throw e;
        throw new RuntimeException("Journal flush failed", err);
    }

    /**
     * Drain one shard's queue and write all pending rows.
     *
     * <p>Crash-safe: rows are first moved to a local snapshot list (drained
     * from the queue up-front), then SQL is executed; on failure the snapshot
     * is re-enqueued at the head so the next scheduled flush will retry.
     * Throws on failure so {@link #flushAll()} can propagate to the caller.
     */
    public void flushShard(int shard) {
        ConcurrentLinkedQueue<PendingRow> q = shardQueues.get(shard);
        if (q.isEmpty()) return;

        var pending = new ArrayList<PendingRow>(Math.min(q.size(), maxBuffer));
        PendingRow row;
        while ((row = q.poll()) != null) {
            pending.add(row);
            totalBuffered.decrementAndGet();
        }
        if (pending.isEmpty()) return;

        try {
            flushRowsDirect(pending);
        } catch (Exception e) {
            for (int i = pending.size() - 1; i >= 0; i--) {
                q.offer(pending.get(i));
            }
            totalBuffered.addAndGet(pending.size());
            log.error("Journal micro-batch flush failed (shard={}, rows={}) — re-queued for retry",
                    shard, pending.size(), e);
            throw new RuntimeException("Journal micro-batch flush failed (shard=" + shard + ")", e);
        }
    }

    /** Multi-row INSERT for a single shard batch; throws on SQL failure.
     * Rows are sorted by (accountAccountId, journalLineId) before insert so
     * concurrent transactions acquire gap locks in a consistent order across
     * all projection instances, preventing InnoDB deadlocks. Deadlocks are
     * also retried a bounded number of times before giving up. */
    private void flushRowsDirect(List<PendingRow> pending) {
        if (pending.isEmpty()) return;

        List<JournalMapper.JournalLineBatchRow> journalRows = toJournalLineRows(pending);
        List<ProjectionEventLogMapper.EventLogBatchRow> eventRows = toEventLogRows(pending);

        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= DEADLOCK_MAX_RETRIES; attempt++) {
            try (SqlSession session = sqlSessionFactory.openSession()) {
                JournalMapper jm = session.getMapper(JournalMapper.class);
                ProjectionEventLogMapper em = session.getMapper(ProjectionEventLogMapper.class);
                jm.batchInsertJournalLines(journalRows);
                em.batchInsertEvents(eventRows);
                session.commit();
                flushBatches.increment();
                flushRows.add(pending.size());
                onFlushedEventCount.accept(pending.size());
                if (attempt > 1) {
                    log.info("Journal batch insert succeeded after retry (attempt={} rows={})", attempt, pending.size());
                }
                return;
            } catch (Exception e) {
                lastError = new RuntimeException("Journal batch insert failed (rows=" + pending.size() + ")", e);
                if (attempt < DEADLOCK_MAX_RETRIES && isDeadlock(e)) {
                    long backoff = DEADLOCK_RETRY_BASE_MS * (1L << (attempt - 1));
                    log.warn("Deadlock detected on journal batch insert (attempt={}/{} rows={}), retrying after {}ms",
                            attempt, DEADLOCK_MAX_RETRIES, pending.size(), backoff, e);
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during deadlock retry", ie);
                    }
                } else {
                    throw lastError;
                }
            }
        }
        throw lastError;
    }

    private static boolean isDeadlock(Throwable t) {
        while (t != null) {
            if (t instanceof SQLException sqlEx && "40001".equals(sqlEx.getSQLState())) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    static List<JournalMapper.JournalLineBatchRow> toJournalLineRows(List<PendingRow> rows) {
        var out = new ArrayList<JournalMapper.JournalLineBatchRow>(rows.size());
        for (PendingRow p : rows) {
            out.add(new JournalMapper.JournalLineBatchRow(
                    p.lineId(), p.journalPk(), p.accountPk(), p.pe().journalLineId(),
                    p.pe().journalId(), p.pe().accountId(), p.pe().balanceType(), p.pe().position(),
                    p.pe().currency(), p.pe().entryType(), p.pe().amount(), p.pe().preBalance(),
                    p.pe().postBalance(), p.pe().configVersion(), p.createdAt()));
        }
        out.sort(Comparator
                .comparing(JournalMapper.JournalLineBatchRow::accountAccountId)
                .thenComparing(JournalMapper.JournalLineBatchRow::journalLineId));
        return out;
    }

    private static List<ProjectionEventLogMapper.EventLogBatchRow> toEventLogRows(List<PendingRow> rows) {
        var out = new ArrayList<ProjectionEventLogMapper.EventLogBatchRow>(rows.size());
        for (PendingRow p : rows) {
            out.add(new ProjectionEventLogMapper.EventLogBatchRow(
                    p.pe().accountId(), p.pe().balanceType(), p.pe().currency(), p.pe().accountSeq(),
                    p.pe().journalLineId(), p.pe().journalId(), p.pe().eventId()));
        }
        return out;
    }

    @Override
    public void close() {
        for (ScheduledExecutorService exec : flushers) {
            exec.shutdown();
        }
        for (ScheduledExecutorService exec : flushers) {
            try {
                if (!exec.awaitTermination(5, TimeUnit.SECONDS)) {
                    exec.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exec.shutdownNow();
            }
        }
        for (int shard = 0; shard < ShardRouting.SHARD_COUNT; shard++) {
            flushShardSafely(shard);
        }
    }

    /** One journal_line + projection_event_log pair ready for micro-batch insert. */
    public record PendingRow(
            int shardIndex,
            long lineId,
            long journalPk,
            long accountPk,
            ProjectionWriter.BalanceEvent pe,
            LocalDateTime createdAt) {}
}

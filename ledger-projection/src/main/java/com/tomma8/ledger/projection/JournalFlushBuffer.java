package com.tomma8.ledger.projection;

import com.tomma8.ledger.dao.mapper.JournalMapper;
import com.tomma8.ledger.dao.mapper.ProjectionEventLogMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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

    /** Enqueue one resolved row. Routes to the per-shard queue and may trigger an early flush. */
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

    /** Drain one shard's queue and write all pending rows. */
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

        try (SqlSession session = sqlSessionFactory.openSession()) {
            JournalMapper jm = session.getMapper(JournalMapper.class);
            ProjectionEventLogMapper em = session.getMapper(ProjectionEventLogMapper.class);
            jm.batchInsertJournalLines(toJournalLineRows(pending));
            em.batchInsertEvents(toEventLogRows(pending));
            session.commit();
            flushBatches.increment();
            flushRows.add(pending.size());
            onFlushedEventCount.accept(pending.size());
        } catch (Exception e) {
            log.error("Journal micro-batch flush failed (shard={}, rows={})", shard, pending.size(), e);
        }
    }

    private static List<JournalMapper.JournalLineBatchRow> toJournalLineRows(List<PendingRow> rows) {
        var out = new ArrayList<JournalMapper.JournalLineBatchRow>(rows.size());
        for (PendingRow p : rows) {
            out.add(new JournalMapper.JournalLineBatchRow(
                    p.lineId(), p.journalPk(), p.accountPk(), p.pe().journalLineId(),
                    p.pe().journalId(), p.pe().accountId(), p.pe().balanceType(), p.pe().position(),
                    p.pe().currency(), p.pe().entryType(), p.pe().amount(), p.pe().preBalance(),
                    p.pe().postBalance(), p.pe().configVersion(), p.createdAt()));
        }
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

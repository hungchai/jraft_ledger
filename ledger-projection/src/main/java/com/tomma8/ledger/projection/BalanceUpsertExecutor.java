package com.tomma8.ledger.projection;

import com.tomma8.ledger.dao.mapper.AccountBalanceMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Async JDBC-batch writer for {@code account_balance} upserts.
 *
 * <p>Without this, {@link ProjectionWriter#writeBalanceBatch} blocks the
 * Kafka consumer thread on every poll batch for one MySQL BATCH upsert
 * (5-15 ms). With this, the consumer thread submits the sorted balance
 * rows and continues; the write happens on a dedicated worker thread.
 *
 * <p><b>Ordering contract</b>:
 * <ul>
 *   <li>Submissions are queued in arrival order (single worker = serial).</li>
 *   <li>{@code submit(...)} returns a {@link CompletableFuture} that completes
 *       AFTER the SQL COMMIT returns successfully. Callers MUST await it
 *       before acking the Kafka offset, otherwise a crash between commit
 *       and ack loses the upsert.</li>
 *   <li>The future completes <i>exceptionally</i> on SQL failure; callers must
 *       reject the Kafka batch so it is redelivered (idempotent upsert via
 *       {@code ON DUPLICATE KEY UPDATE}).</li>
 * </ul>
 *
 * <p><b>Backpressure</b>: bounded queue + {@code offer(timeout)} so a slow
 * MySQL or runaway producer surfaces as a {@link BalanceUpsertBackpressureException}
 * instead of an unbounded OOM.
 */
public class BalanceUpsertExecutor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BalanceUpsertExecutor.class);

    private final SqlSessionFactory sqlSessionFactory;
    private final int queueCapacity;
    private final long submitTimeoutMs;
    private final BlockingQueue<Task> queue;
    private final Thread worker;
    private final AtomicBoolean running = new AtomicBoolean(true);

    // Metrics
    private final AtomicInteger depth = new AtomicInteger();
    private final AtomicInteger rejectedCount = new AtomicInteger();
    private final Timer upsertTimer;

    public BalanceUpsertExecutor(SqlSessionFactory sqlSessionFactory,
                                  MeterRegistry meterRegistry,
                                  int queueCapacity,
                                  long submitTimeoutMs) {
        this.sqlSessionFactory = sqlSessionFactory;
        this.queueCapacity = Math.max(1, queueCapacity);
        this.submitTimeoutMs = Math.max(1, submitTimeoutMs);
        this.queue = new ArrayBlockingQueue<>(this.queueCapacity);
        this.worker = new Thread(this::runLoop, "balance-upsert-executor");
        this.worker.setDaemon(true);
        this.worker.start();

        Gauge.builder("ledger.projection.balance.upsert.queue.depth", depth, AtomicInteger::doubleValue)
                .description("Pending balance upsert batches awaiting JDBC commit.")
                .register(meterRegistry);
        Gauge.builder("ledger.projection.balance.upsert.rejected", rejectedCount, AtomicInteger::doubleValue)
                .description("Balance upsert submissions rejected (backpressure / shutdown).")
                .register(meterRegistry);
        this.upsertTimer = Timer.builder("ledger.projection.balance.upsert.duration")
                .description("JDBC BATCH upsert commit time for account_balance.")
                .publishPercentiles(0.5, 0.95, 0.99)
                .minimumExpectedValue(java.time.Duration.ofMillis(1))
                .maximumExpectedValue(java.time.Duration.ofSeconds(10))
                .register(meterRegistry);

        log.info("BalanceUpsertExecutor started: queueCapacity={} submitTimeoutMs={}",
                this.queueCapacity, this.submitTimeoutMs);
    }

    /**
     * Submit one sorted batch. Returns a future the caller MUST await before
     * acknowledging the Kafka offset. Throws {@link BalanceUpsertBackpressureException}
     * if the queue is full past {@code submitTimeoutMs} — caller should NOT ack
     * so Kafka redelivers.
     */
    public CompletableFuture<Void> submit(List<BalanceUpdate> sortedRows) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        if (!running.get()) {
            rejectedCount.incrementAndGet();
            future.completeExceptionally(new IllegalStateException("BalanceUpsertExecutor is shut down"));
            return future;
        }
        if (sortedRows == null || sortedRows.isEmpty()) {
            future.complete(null);
            return future;
        }
        Task task = new Task(sortedRows, future);
        try {
            boolean offered = queue.offer(task, submitTimeoutMs, TimeUnit.MILLISECONDS);
            if (!offered) {
                rejectedCount.incrementAndGet();
                future.completeExceptionally(new BalanceUpsertBackpressureException(
                        "Balance upsert queue full for " + submitTimeoutMs + "ms (capacity=" + queueCapacity + ")"));
                return future;
            }
            depth.set(queue.size());
            return future;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            rejectedCount.incrementAndGet();
            future.completeExceptionally(ie);
            return future;
        }
    }

    private void runLoop() {
        while (running.get()) {
            Task task;
            try {
                task = queue.poll(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            if (task == null) continue;
            depth.set(queue.size());
            try {
                long t0 = System.nanoTime();
                doUpsert(task.rows);
                upsertTimer.record(System.nanoTime() - t0, TimeUnit.NANOSECONDS);
                task.future.complete(null);
            } catch (Throwable t) {
                log.error("Balance upsert failed (rows={}) — future will complete exceptionally",
                        task.rows.size(), t);
                task.future.completeExceptionally(t);
            }
        }
        // Drain remaining on shutdown so in-flight batches complete their futures
        // (caller is waiting; without this they hang on future.get until timeout).
        Task tail;
        while ((tail = queue.poll()) != null) {
            try {
                doUpsert(tail.rows);
                tail.future.complete(null);
            } catch (Throwable t) {
                tail.future.completeExceptionally(t);
            }
        }
    }

    private void doUpsert(List<BalanceUpdate> rows) {
        try (SqlSession session = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
            AccountBalanceMapper bm = session.getMapper(AccountBalanceMapper.class);
            for (BalanceUpdate bu : rows) {
                bm.upsertBalance(bu.accountPk(), bu.accountId(), bu.balanceType(), bu.currency(),
                        bu.amount(), bu.position(), bu.accountSeq(), bu.lastJournalId());
            }
            session.flushStatements();
            session.commit();
        }
    }

    @Override
    public void close() {
        running.set(false);
        worker.interrupt();
        try {
            worker.join(5_000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private record Task(List<BalanceUpdate> rows, CompletableFuture<Void> future) {}

    /** Thrown by {@link #submit} when the bounded queue stays full past submitTimeoutMs. */
    public static class BalanceUpsertBackpressureException extends RuntimeException {
        public BalanceUpsertBackpressureException(String message) {
            super(message);
        }
    }

    // Visible for tests
    int queueDepth() { return queue.size(); }
    // Visible for tests
    ArrayList<BalanceUpdate> snapshotQueue() {
        var snap = new ArrayList<BalanceUpdate>();
        queue.forEach(t -> snap.addAll(t.rows));
        return snap;
    }
}
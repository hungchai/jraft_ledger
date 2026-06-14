package com.tomma8.ledger.event;

import com.tomma8.ledger.domain.event.BalanceChangeEvent;
import com.tomma8.ledger.rocksdb.OutboxStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Background scanner that drains CF_OUTBOX and publishes events to Kafka.
 *
 * Design:
 * - Polls OutboxStore.readPending(batchSize) on a fixed interval
 * - For each event: publish to Kafka via KafkaEventPublisher
 * - On Kafka ack: delete from OutboxStore (markSent)
 * - On failure: skip, retry next cycle (at-least-once delivery)
 * - Tracks pending count for monitoring
 */
public class AsyncOutboxPublisher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AsyncOutboxPublisher.class);

    private final OutboxStore outboxStore;
    private final KafkaEventPublisher kafkaPublisher;
    private final EmitGate emitGate;
    private final ScheduledExecutorService scheduler;
    private final Duration pollInterval;
    private final int batchSize;

    private final AtomicLong publishedCount = new AtomicLong(0);
    private final AtomicLong failedCount = new AtomicLong(0);
    private final AtomicLong lastScanPending = new AtomicLong(0);
    private final AtomicLong lastScanDurationMs = new AtomicLong(0);

    private volatile boolean running;

    /**
     * Create and start the async outbox publisher.
     *
     * @param outboxStore     the RocksDB-backed outbox store
     * @param kafkaPublisher  the Kafka publisher (same instance wired to StateMachine)
     * @param pollInterval    interval between CF_OUTBOX scans
     * @param batchSize       max events to process per scan
     */
    public AsyncOutboxPublisher(OutboxStore outboxStore,
                                KafkaEventPublisher kafkaPublisher,
                                Duration pollInterval,
                                int batchSize) {
        this(outboxStore, kafkaPublisher, new EmitGate(), pollInterval, batchSize);
    }

    public AsyncOutboxPublisher(OutboxStore outboxStore,
                                KafkaEventPublisher kafkaPublisher,
                                EmitGate emitGate,
                                Duration pollInterval,
                                int batchSize) {
        this.outboxStore = outboxStore;
        this.kafkaPublisher = kafkaPublisher;
        this.emitGate = emitGate;
        this.pollInterval = pollInterval;
        this.batchSize = batchSize;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "outbox-publisher");
            t.setDaemon(true);
            return t;
        });
        this.running = true;
        scheduler.scheduleWithFixedDelay(this::scanAndPublish,
                pollInterval.toMillis(),
                pollInterval.toMillis(),
                TimeUnit.MILLISECONDS);
        log.info("AsyncOutboxPublisher started: pollInterval={} batchSize={}", pollInterval, batchSize);
    }

    private void scanAndPublish() {
        if (!running) return;
        if (!emitGate.isEnabled()) return;
        long start = System.currentTimeMillis();
        try {
            List<BalanceChangeEvent> events = outboxStore.readPending(batchSize);
            lastScanPending.set(events.size());
            if (events.isEmpty()) return;

            log.debug("Outbox scan found {} pending events", events.size());
            int ok = 0;
            int fail = 0;

            for (BalanceChangeEvent event : events) {
                try {
                    kafkaPublisher.onEvent(event);
                    // Callback-driven deletion: KafkaEventPublisher send-callback
                    // calls outboxStore.markSent(eventId) on Kafka ack success.
                    // If callback never fires (crash), event stays in outbox and
                    // will be re-scanned on next poll.
                    ok++;
                } catch (Exception e) {
                    log.warn("Failed to publish outbox event {} (account={} balanceType={})",
                            event.eventId(), event.accountId(), event.balanceType(), e);
                    fail++;
                }
            }
            publishedCount.addAndGet(ok);
            failedCount.addAndGet(fail);
            if (fail > 0) {
                log.warn("Outbox scan complete: ok={} failed={}", ok, fail);
            }
        } catch (Exception e) {
            log.error("Outbox scan failed", e);
        } finally {
            lastScanDurationMs.set(System.currentTimeMillis() - start);
        }
    }

    // ── Metrics (read by LedgerConfig for Micrometer gauges) ────

    public long getPublishedCount() { return publishedCount.get(); }
    public long getFailedCount() { return failedCount.get(); }
    public long getLastScanPending() { return lastScanPending.get(); }
    public long getLastScanDurationMs() { return lastScanDurationMs.get(); }

    // ── Lifecycle ───────────────────────────────────────────────

    @Override
    public void close() {
        running = false;
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("AsyncOutboxPublisher stopped: published={} failed={}",
                publishedCount.get(), failedCount.get());
    }
}

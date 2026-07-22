package com.tomma8.ledger.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tomma8.ledger.domain.event.AccountCreatedEvent;
import com.tomma8.ledger.domain.event.BalanceChangeEvent;
import com.tomma8.ledger.domain.event.JournalEventEnvelope;
import com.tomma8.ledger.domain.event.LedgerEventListener;
import com.tomma8.ledger.rocksdb.OutboxStore;
import com.tomma8.ledger.util.LedgerMappers;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes BalanceChangeEvents / journal envelopes to Kafka.
 *
 * <p>Two async tiers:
 * <ol>
 *   <li><b>In-process in-memory queue</b> (this class). The hot path
 *       {@link #onPosting} no longer calls {@code producer.send()} directly on
 *       the FSM-apply thread. Instead it serialises the envelope once (CPU work
 *       is unavoidable; we still own the byte buffer) and enqueues a small
 *       immutable record onto a {@link LinkedBlockingQueue}. Dedicated drain
 *       threads dequeue and call {@code producer.send()} with the original
 *       callback. This breaks the
 *       {@code FSM-apply-thread → Kafka-BufferPool → max.block.ms} critical
 *       path that produced p99 = 3.1 s under 100 VU.</li>
 *   <li><b>RocksDB outbox + AsyncOutboxPublisher</b>. Already in place; catches
 *       crash-recovery cases (envelope persisted to CF_OUTBOX before node
 *       crash, never reached Kafka). The in-process queue is the
 *       fast-path drain; the RocksDB scanner is the durable safety net.</li>
 * </ol>
 *
 * <p>At-least-once semantics: the envelope is written to CF_OUTBOX inside the
 * same Raft-applied WriteBatch as the balance update (atomic). The in-process
 * queue is best-effort. If the queue is full, we fall back to the legacy sync
 * send (still at-least-once, just blocking the request thread until the
 * producer buffer drains).
 */
public class KafkaEventPublisher implements LedgerEventListener, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);
    private static final ObjectMapper mapper = LedgerMappers.get();

    private final KafkaProducer<String, String> producer;
    private final String balanceChangeTopic;
    private final String accountTopic;
    private OutboxStore outboxStore;

    // Async tier-1: bounded queue + N dedicated drain threads.
    // offer() with a short timeout is the backpressure point — under 500 VU the
    // queue drains fast enough that the FSM apply thread almost never blocks.
    private final BlockingQueue<PendingSend> inflightQueue;
    private final int drainThreads;
    private final Thread[] drainHandles;
    private final AtomicBoolean running = new AtomicBoolean(true);

    // Metrics — read by LedgerConfig for Micrometer gauges.
    private final AtomicLong enqueuedCount = new AtomicLong(0);
    private final AtomicLong droppedToSyncCount = new AtomicLong(0);
    private final AtomicLong drainedCount = new AtomicLong(0);
    private final AtomicLong drainErrorCount = new AtomicLong(0);
    private final AtomicLong queuePeakDepth = new AtomicLong(0);

    public KafkaEventPublisher(String bootstrapServers, String balanceChangeTopic) {
        this(bootstrapServers, balanceChangeTopic, "ledger.account.v1", 10_000, 2);
    }

    public KafkaEventPublisher(String bootstrapServers, String balanceChangeTopic, String accountTopic) {
        this(bootstrapServers, balanceChangeTopic, accountTopic, 10_000, 2);
    }

    public KafkaEventPublisher(String bootstrapServers, String balanceChangeTopic, String accountTopic,
                               int queueCapacity, int drainThreads) {
        this(bootstrapServers, balanceChangeTopic, accountTopic, queueCapacity, drainThreads, "ledger-node");
    }

    public KafkaEventPublisher(String bootstrapServers, String balanceChangeTopic, String accountTopic,
                               int queueCapacity, int drainThreads, String clientIdSuffix) {
        this.balanceChangeTopic = balanceChangeTopic;
        this.accountTopic = accountTopic;
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        // Idempotent producer: tolerates transient broker failures without producing
        // duplicates on retry. acks=all + retries=MAX_VALUE + max.in.flight=5 is the
        // Kafka-recommended safe combination (broker guarantees no duplicates and
        // preserves order with up to 5 in-flight).
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        // Fail-fast when Kafka is unreachable so the node runs WITHOUT Kafka and auto-uses it when it
        // returns — no static toggle needed. The constructor never connects (lazy), so startup is fine
        // broker-up or broker-down. The risk is send(): with the 60s default max.block.ms, every send
        // during an outage blocks the outbox-drain thread up to a minute fetching metadata. We bound it
        // so a down broker makes send() fail fast → AsyncOutboxPublisher counts the failure, leaves the
        // envelope in CF_OUTBOX (durable), and retries next poll; once the broker is back, sends succeed
        // and the backlog drains. delivery.timeout.ms must be >= request.timeout.ms + linger.ms.
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000);          // cap send()/metadata wait (def 60s)
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30000);   // per-request broker wait (def 30s)
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120000);  // total time before a send is failed
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);                // batch linger for throughput
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 65536);           // 64 KiB batch — saturate per-broker socket
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 268435456L);   // 256 MiB producer buffer
        String host = (clientIdSuffix == null || clientIdSuffix.isBlank()) ? "ledger-node" : clientIdSuffix;
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "ledger-node-" + host);
        this.producer = new KafkaProducer<>(props);

        this.inflightQueue = new LinkedBlockingQueue<>(Math.max(1, queueCapacity));
        this.drainThreads = Math.max(1, drainThreads);
        this.drainHandles = new Thread[this.drainThreads];
        for (int i = 0; i < this.drainThreads; i++) {
            Thread t = new Thread(this::drainLoop, "kafka-drain-" + i);
            t.setDaemon(true);
            this.drainHandles[i] = t;
            t.start();
        }
        log.info("KafkaEventPublisher async tier-1: queueCapacity={} drainThreads={}", queueCapacity, this.drainThreads);
    }

    public void setOutboxStore(OutboxStore outboxStore) {
        this.outboxStore = outboxStore;
    }

    @Override
    public void onEvent(BalanceChangeEvent event) {
        // Cold path: legacy per-line emission (used only when the listener
        // override doesn't take the envelope route). Goes straight to the
        // in-memory queue so the same async-tier applies.
        try {
            String key = event.accountId() + ":" + event.balanceType() + ":" + event.currency();
            String value = mapper.writeValueAsString(event);
            PendingSend ps = new PendingSend(balanceChangeTopic, key, value, outboxStore, event.eventId(), null);
            enqueueOrSync(ps, event.eventId());
        } catch (Exception e) {
            log.error("Failed to serialize balance event {}", event.eventId(), e);
        }
    }

    /**
     * Hot path: bundled per-journal envelope. The previous implementation
     * called {@code producer.send()} on the FSM-apply thread, which under
     * 100+ VU caused the Kafka producer's buffer pool to fill and
     * {@code send()} to block up to {@code max.block.ms = 2 s}. That blocked
     * the FSM apply thread, which blocked the Raft apply wait, which blocked
     * the HTTP request — p99 = 3.1 s.
     *
     * <p>New flow: serialise the envelope once (CPU-bound, unavoidable), then
     * enqueue onto a bounded in-memory queue. The FSM apply thread returns
     * immediately. A dedicated drain thread calls {@code producer.send()}
     * with the original callback, so {@code outboxStore.markJournalSent()}
     * still runs on Kafka ack and the at-least-once guarantee is preserved.
     */
    @Override
    public void onPosting(JournalEventEnvelope envelope) {
        try {
            String key = envelope.journalId();
            String value = mapper.writeValueAsString(envelope);
            PendingSend ps = new PendingSend(balanceChangeTopic, key, value,
                    outboxStore, null, envelope.journalId());
            enqueueOrSync(ps, envelope.journalId());
        } catch (Exception e) {
            log.error("Failed to serialize journal envelope {}", envelope.journalId(), e);
        }
    }

    @Override
    public void onAccountCreated(AccountCreatedEvent event) {
        try {
            String value = mapper.writeValueAsString(event);
            PendingSend ps = new PendingSend(accountTopic, event.accountId(), value,
                    null, null, null);
            enqueueOrSync(ps, event.accountId());
        } catch (Exception e) {
            log.error("Failed to serialize account event {}", event.accountId(), e);
        }
    }

    /**
     * Try to enqueue. If the queue is full (backpressure), fall back to
     * direct {@code producer.send()} on the caller's thread — at-least-once
     * is preserved (the envelope is already in CF_OUTBOX), only latency
     * regresses. Count the fallback so we can see backpressure in metrics.
     */
    private void enqueueOrSync(PendingSend ps, String traceId) {
        if (!running.get()) {
            // Shutting down — send synchronously so we don't drop the event.
            sendNow(ps);
            return;
        }
        boolean accepted = inflightQueue.offer(ps);
        if (accepted) {
            enqueuedCount.incrementAndGet();
            int depth = inflightQueue.size();
            if (depth > queuePeakDepth.get()) queuePeakDepth.set(depth);
        } else {
            // Queue full → direct send on the FSM apply thread (backpressure
            // is the only failure mode that should ever hit this branch).
            droppedToSyncCount.incrementAndGet();
            log.warn("Async outbox queue full (depth={} capacity={}); falling back to sync send for {}",
                    inflightQueue.size(), inflightQueue.size() + inflightQueue.remainingCapacity(), traceId);
            sendNow(ps);
        }
    }

    private void sendNow(PendingSend ps) {
        try {
            ProducerRecord<String, String> record = new ProducerRecord<>(ps.topic, ps.key, ps.value);
            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    log.error("Failed to publish {} (key={}): {}", ps.topic, ps.key, exception.getMessage());
                } else if (ps.outboxStore != null) {
                    if (ps.eventId != null) {
                        ps.outboxStore.markSent(ps.eventId);
                    } else if (ps.journalId != null) {
                        ps.outboxStore.markJournalSent(ps.journalId);
                    }
                }
            });
        } catch (Exception e) {
            log.error("Sync send failed for key={} topic={}", ps.key, ps.topic, e);
        }
    }

    private void drainLoop() {
        // Thief-batched dequeue: pull as many envelopes as available in a
        // tight loop and hand them to the producer. The producer's internal
        // batching (linger.ms=5, batch.size=64KiB) does the real batching
        // for throughput; this loop just keeps the producer fed.
        final List<PendingSend> batch = new ArrayList<>(64);
        while (running.get()) {
            try {
                PendingSend first = inflightQueue.poll(50, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                batch.add(first);
                inflightQueue.drainTo(batch, 63);
                for (int i = 0; i < batch.size(); i++) {
                    sendNow(batch.get(i));
                    drainedCount.incrementAndGet();
                }
                batch.clear();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                drainErrorCount.incrementAndGet();
                log.error("drainLoop unexpected error", t);
                batch.clear();
            }
        }
        // Drain remaining on shutdown.
        PendingSend leftover;
        while ((leftover = inflightQueue.poll()) != null) {
            sendNow(leftover);
        }
    }

    public void flush() {
        producer.flush();
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) return;
        for (Thread t : drainHandles) {
            try {
                t.join(TimeUnit.SECONDS.toMillis(2));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        producer.close();
        log.info("KafkaEventPublisher closed: enqueued={} droppedSync={} drained={} drainErrors={} peakDepth={}",
                enqueuedCount.get(), droppedToSyncCount.get(), drainedCount.get(),
                drainErrorCount.get(), queuePeakDepth.get());
    }

    // ── Metrics (read by LedgerConfig for Micrometer gauges) ────

    public int getQueueDepth() { return inflightQueue.size(); }
    public int getQueueCapacity() { return inflightQueue.size() + inflightQueue.remainingCapacity(); }
    public long getEnqueuedCount() { return enqueuedCount.get(); }
    public long getDroppedToSyncCount() { return droppedToSyncCount.get(); }
    public long getDrainedCount() { return drainedCount.get(); }
    public long getDrainErrorCount() { return drainErrorCount.get(); }
    public long getQueuePeakDepth() { return queuePeakDepth.get(); }

    // Pre-serialized Kafka record + bookkeeping. Avoids re-serialising in the
    // drain thread (the original code re-serialised the envelope every call,
    // which doubled CPU on the hot path).
    private record PendingSend(String topic,
                               String key,
                               String value,
                               OutboxStore outboxStore,
                               String eventId,
                               String journalId) {
    }
}

package com.tomma8.ledger.rocksdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tomma8.ledger.domain.event.BalanceChangeEvent;
import com.tomma8.ledger.util.LedgerMappers;
import org.rocksdb.RocksIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * RocksDB-backed outbox for at-least-once event delivery to Kafka.
 * Writes events as part of the same RocksDB WriteBatch as balance updates.
 * AsyncOutboxPublisher scans and drains this store.
 */
public class OutboxStore {

    private static final Logger log = LoggerFactory.getLogger(OutboxStore.class);
    private static final ObjectMapper mapper = LedgerMappers.get();
    private static final byte[] OUTBOX_PREFIX = "outbox:".getBytes(StandardCharsets.UTF_8);

    private final RocksDBManager rocksDBManager;
    private final ConcurrentLinkedQueue<BalanceChangeEvent> pending = new ConcurrentLinkedQueue<>();

    public OutboxStore(RocksDBManager rocksDBManager) {
        this.rocksDBManager = rocksDBManager;
    }

    /**
     * Queue an event for writing in the next WriteBatch.
     */
    public void enqueue(BalanceChangeEvent event) {
        pending.offer(event);
    }

    /**
     * Write all pending events to RocksDB CF_OUTBOX atomically.
     */
    public void flush() {
        if (pending.isEmpty() || rocksDBManager == null) return;
        BalanceChangeEvent event;
        try {
            while ((event = pending.poll()) != null) {
                byte[] key = outboxKey(event.eventId());
                byte[] value = mapper.writeValueAsBytes(event);
                rocksDBManager.put("outbox", key, value);
            }
        } catch (Exception e) {
            log.error("Failed to flush outbox", e);
        }
    }

    /**
     * Scan CF_OUTBOX and return unsent events up to limit.
     * Caller must call {@link #markSent} after successful Kafka publish.
     */
    public List<BalanceChangeEvent> readPending(int limit) {
        if (rocksDBManager == null || !rocksDBManager.isOpen()) return List.of();
        List<BalanceChangeEvent> events = new ArrayList<>();
        try (RocksIterator iter = rocksDBManager.getRocksDB().newIterator(
                rocksDBManager.getHandle("outbox"))) {
            iter.seek(OUTBOX_PREFIX);
            while (iter.isValid() && events.size() < limit) {
                byte[] key = iter.key();
                if (key == null || !startsWith(key, OUTBOX_PREFIX)) break;
                byte[] value = iter.value();
                if (value != null && value.length > 0) {
                    try {
                        BalanceChangeEvent event = mapper.readValue(value, BalanceChangeEvent.class);
                        events.add(event);
                    } catch (Exception e) {
                        log.warn("Failed to deserialize outbox event, key={}",
                                new String(key, StandardCharsets.UTF_8), e);
                    }
                }
                iter.next();
            }
        } catch (Exception e) {
            log.error("Failed to scan outbox", e);
        }
        return events;
    }

    /**
     * Delete an outbox event after successful Kafka publish.
     */
    public void markSent(String eventId) {
        if (rocksDBManager == null || !rocksDBManager.isOpen()) return;
        try {
            rocksDBManager.delete("outbox", outboxKey(eventId));
        } catch (Exception e) {
            log.error("Failed to delete outbox event {}", eventId, e);
        }
    }

    /**
     * Count pending outbox events by scanning CF_OUTBOX.
     */
    public long pendingCount() {
        if (rocksDBManager == null || !rocksDBManager.isOpen()) return pending.size();
        long count = 0;
        try (RocksIterator iter = rocksDBManager.getRocksDB().newIterator(
                rocksDBManager.getHandle("outbox"))) {
            iter.seek(OUTBOX_PREFIX);
            while (iter.isValid()) {
                byte[] key = iter.key();
                if (key == null || !startsWith(key, OUTBOX_PREFIX)) break;
                count++;
                iter.next();
            }
        } catch (Exception e) {
            log.error("Failed to count outbox events", e);
        }
        return count + pending.size();
    }

    private static byte[] outboxKey(String eventId) {
        return ("outbox:" + eventId).getBytes(StandardCharsets.UTF_8);
    }

    private static boolean startsWith(byte[] src, byte[] prefix) {
        if (src.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (src[i] != prefix[i]) return false;
        }
        return true;
    }
}

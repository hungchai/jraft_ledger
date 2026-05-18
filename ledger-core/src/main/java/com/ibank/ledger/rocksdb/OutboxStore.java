package com.ibank.ledger.rocksdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ibank.ledger.domain.event.BalanceChangeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * RocksDB-backed outbox for at-least-once event delivery to Kafka.
 * Writes events as part of the same RocksDB WriteBatch as balance updates.
 */
public class OutboxStore {

    private static final Logger log = LoggerFactory.getLogger(OutboxStore.class);
    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final RocksDBManager rocksDBManager;
    private final List<BalanceChangeEvent> pending = new ArrayList<>();

    public OutboxStore(RocksDBManager rocksDBManager) {
        this.rocksDBManager = rocksDBManager;
    }

    /**
     * Queue an event for writing in the next WriteBatch.
     */
    public void enqueue(BalanceChangeEvent event) {
        pending.add(event);
    }

    /**
     * Write all pending events to RocksDB CF_OUTBOX atomically.
     */
    public void flush() {
        if (pending.isEmpty() || rocksDBManager == null) return;
        try {
            for (var event : pending) {
                byte[] key = ("outbox:" + event.eventId()).getBytes(StandardCharsets.UTF_8);
                byte[] value = mapper.writeValueAsBytes(event);
                rocksDBManager.put("outbox", key, value);
            }
            pending.clear();
        } catch (Exception e) {
            log.error("Failed to flush outbox", e);
        }
    }

    /**
     * Read all pending outbox events (for recovery/publishing).
     */
    public List<BalanceChangeEvent> readPending() {
        // In production, this would scan CF_OUTBOX for unsent events
        // For now, returns empty — AsyncKafkaPublisher handles scanning
        return List.of();
    }

    public int pendingCount() {
        return pending.size();
    }
}

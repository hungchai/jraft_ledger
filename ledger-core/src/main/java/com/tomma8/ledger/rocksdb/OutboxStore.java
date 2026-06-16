package com.tomma8.ledger.rocksdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tomma8.ledger.domain.event.BalanceChangeEvent;
import com.tomma8.ledger.domain.event.JournalEventEnvelope;
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
    private static final byte[] OUTBOX_JOURNAL_PREFIX = "outbox:journal:".getBytes(StandardCharsets.UTF_8);

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
     * Scan CF_OUTBOX and return unsent per-line events (legacy path; used
     * by tests that pre-date the journal-envelope wire format). Caller
     * must call {@link #markSent} after successful Kafka publish.
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
                // Skip journal-envelope entries (different prefix)
                if (startsWith(key, OUTBOX_JOURNAL_PREFIX)) { iter.next(); continue; }
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
     * Scan CF_OUTBOX and return unsent journal envelopes up to limit.
     * Each envelope bundles all line events for one journal — caller must
     * call {@link #markJournalSent} on Kafka ack. Returns envelopes in
     * journalId order (sorted by CF key prefix).
     */
    public List<JournalEventEnvelope> readPendingJournals(int limit) {
        if (rocksDBManager == null || !rocksDBManager.isOpen()) return List.of();
        List<JournalEventEnvelope> envelopes = new ArrayList<>();
        try (RocksIterator iter = rocksDBManager.getRocksDB().newIterator(
                rocksDBManager.getHandle("outbox"))) {
            iter.seek(OUTBOX_JOURNAL_PREFIX);
            while (iter.isValid() && envelopes.size() < limit) {
                byte[] key = iter.key();
                if (key == null || !startsWith(key, OUTBOX_JOURNAL_PREFIX)) break;
                byte[] value = iter.value();
                if (value != null && value.length > 0) {
                    try {
                        JournalEventEnvelope env = mapper.readValue(value, JournalEventEnvelope.class);
                        envelopes.add(env);
                    } catch (Exception e) {
                        log.warn("Failed to deserialize outbox envelope, key={}",
                                new String(key, StandardCharsets.UTF_8), e);
                    }
                }
                iter.next();
            }
        } catch (Exception e) {
            log.error("Failed to scan outbox journals", e);
        }
        return envelopes;
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
     * Delete an outbox journal-envelope entry after successful Kafka publish.
     * Key format: {@code outbox:journal:<journalId>}.
     */
    public void markJournalSent(String journalId) {
        if (rocksDBManager == null || !rocksDBManager.isOpen()) return;
        try {
            rocksDBManager.delete("outbox", outboxJournalKey(journalId));
        } catch (Exception e) {
            log.error("Failed to delete outbox journal envelope {}", journalId, e);
        }
    }

    /**
     * Persist a journal envelope to CF_OUTBOX. Caller must invoke
     * {@link #markJournalSent} on Kafka ack.
     */
    public void enqueueJournal(String journalId, byte[] value) {
        if (rocksDBManager == null || !rocksDBManager.isOpen()) return;
        try {
            rocksDBManager.put("outbox", outboxJournalKey(journalId), value);
        } catch (Exception e) {
            log.error("Failed to enqueue outbox journal envelope {}", journalId, e);
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

    private static byte[] outboxJournalKey(String journalId) {
        return ("outbox:journal:" + journalId).getBytes(StandardCharsets.UTF_8);
    }

    private static boolean startsWith(byte[] src, byte[] prefix) {
        if (src.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (src[i] != prefix[i]) return false;
        }
        return true;
    }
}

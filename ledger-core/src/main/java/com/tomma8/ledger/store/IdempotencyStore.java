package com.tomma8.ledger.store;

import com.tomma8.ledger.domain.model.IdempotencyEntry;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Idempotency guard: maps requestId → completed journal entry.
 *
 * <p>Failed/rejected requests are NOT stored — a retry with the same requestId re-executes,
 * which is safe because the original attempt mutated no state.
 *
 * <p>TTL: entries older than {@code ttlMillis} are evicted on read (lazy) and by
 * {@link #evictExpired()} (called periodically from a scheduler). This bounds memory
 * growth in long-running stress tests where new requestIds keep arriving.
 *
 * <p>Back-compat: pre-TTL entries (persisted with createdAtMillis=0) are treated as
 * expired and removed on the next sweep — they cannot satisfy future retries
 * reliably anyway since they pre-date the TTL feature.
 *
 * <p>The store participates in Raft snapshots for cross-node determinism. Only completed
 * entries are included, and all nodes see the same apply order → identical store contents.
 */
public class IdempotencyStore {

    private final ConcurrentHashMap<String, IdempotencyEntry> store = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final Clock clock;

    public IdempotencyStore() {
        this(Duration.ofDays(30), Clock.systemUTC());
    }

    public IdempotencyStore(Duration ttl, Clock clock) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive, got: " + ttl);
        }
        this.ttlMillis = ttl.toMillis();
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    /** Returns the journalId if this requestId is still within TTL, null otherwise. */
    public String get(String requestId) {
        IdempotencyEntry entry = store.get(requestId);
        if (entry == null) return null;
        if (isExpired(entry)) {
            // CAS remove — another thread may have replaced it
            store.remove(requestId, entry);
            return null;
        }
        return entry.journalId();
    }

    public void put(String requestId, String journalId) {
        store.put(requestId, new IdempotencyEntry(requestId, journalId, clock.millis()));
    }

    public boolean contains(String requestId) {
        return get(requestId) != null;
    }

    public void remove(String requestId) {
        store.remove(requestId);
    }

    public void clear() {
        store.clear();
    }

    public Map<String, String> getAll() {
        // Strip expired entries during the snapshot dump so a TTL-restored node
        // doesn't carry stale (already-evicted) keys.
        long now = clock.millis();
        var result = new HashMap<String, String>(store.size());
        for (var e : store.entrySet()) {
            if (!isExpired(e.getValue(), now)) {
                result.put(e.getKey(), e.getValue().journalId());
            }
        }
        return Map.copyOf(result);
    }

    public int size() {
        return store.size();
    }

    /** Returns count of entries removed. O(n) but n is bounded by the TTL. */
    public int evictExpired() {
        long now = clock.millis();
        int[] removed = {0};
        store.entrySet().removeIf(e -> {
            if (isExpired(e.getValue(), now)) {
                removed[0]++;
                return true;
            }
            return false;
        });
        return removed[0];
    }

    public long getTtlMillis() {
        return ttlMillis;
    }

    private boolean isExpired(IdempotencyEntry entry) {
        return isExpired(entry, clock.millis());
    }

    private boolean isExpired(IdempotencyEntry entry, long now) {
        long created = entry.createdAtMillis();
        // createdAtMillis == 0L → pre-TTL legacy entry → treat as expired (back-compat)
        if (created == 0L) return true;
        return (now - created) > ttlMillis;
    }
}

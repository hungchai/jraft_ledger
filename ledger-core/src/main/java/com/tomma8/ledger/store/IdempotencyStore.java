package com.tomma8.ledger.store;

import com.tomma8.ledger.domain.model.IdempotencyEntry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Idempotency dedup store. The authoritative copy of every entry is the RocksDB
 * `idempotency` CF (written in the same WriteBatch as the journal on apply). This
 * class is a BOUNDED in-heap LRU cache over that CF — NOT an unbounded map.
 *
 * Why bounded: a plain map here grew one entry per requestId forever and exhausted
 * the JVM heap under sustained load (observed: 220k+ entries → leader heap OOM,
 * independent of the native WriteBatch leak). It was also serialized whole into the
 * snapshot blob. Capping it (LRU) bounds both heap and snapshot size; correctness is
 * preserved because a cache miss falls back to the durable RocksDB CF, so a dedup
 * lookup for any requestId ever applied still succeeds. Mirrors the journalStore fix
 * (heap cache over the RocksDB journal CF). Cap via LEDGER_IDEMPOTENCY_CACHE.
 */
public class IdempotencyStore {

    private static int cap() {
        try {
            String v = System.getenv("LEDGER_IDEMPOTENCY_CACHE");
            return v == null ? 100_000 : Integer.parseInt(v.trim());
        } catch (Exception e) {
            return 100_000;
        }
    }

    private final int maxEntries;

    public IdempotencyStore() {
        this(cap());
    }

    /** Test/explicit cap. */
    public IdempotencyStore(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    // access-order LinkedHashMap → LRU eviction of the least-recently-used requestId
    // once the cap is exceeded. synchronizedMap: apply is single-threaded, but reads
    // (balance/journal queries) can touch it concurrently.
    private final Map<String, IdempotencyEntry> store = Collections.synchronizedMap(
            new LinkedHashMap<>(1024, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, IdempotencyEntry> eldest) {
                    return size() > maxEntries;
                }
            });

    // RocksDB-CF lookup for cache misses (set by the state machine when RocksDB is
    // wired). Null in pure in-memory/test mode, where the heap map is the only store.
    private volatile Function<String, IdempotencyEntry> fallback;

    public void setFallback(Function<String, IdempotencyEntry> fallback) {
        this.fallback = fallback;
    }

    public Optional<IdempotencyEntry> get(String requestId) {
        IdempotencyEntry e = store.get(requestId);
        if (e != null) return Optional.of(e);
        Function<String, IdempotencyEntry> fb = fallback;
        if (fb != null) {
            IdempotencyEntry fromDb = fb.apply(requestId);
            if (fromDb != null) {
                store.put(requestId, fromDb);   // warm the cache
                return Optional.of(fromDb);
            }
        }
        return Optional.empty();
    }

    public void put(String requestId, IdempotencyEntry entry) {
        store.put(requestId, entry);
    }

    public boolean contains(String requestId) {
        return get(requestId).isPresent();
    }

    public void remove(String requestId) {
        store.remove(requestId);
    }

    public void clear() {
        store.clear();
    }

    /** Snapshot of the currently-cached entries (bounded by the LRU cap). The full
     *  history lives in the RocksDB CF; this is only the hot window. */
    public Map<String, IdempotencyEntry> getAll() {
        synchronized (store) {
            return Map.copyOf(store);
        }
    }

    public int size() {
        return store.size();
    }
}

package com.tomma8.ledger.store;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Idempotency guard: maps requestId → journalId for completed (successful) requests only.
 * Failed/rejected requests are NOT stored — a retry with the same requestId re-executes,
 * which is safe because the original attempt mutated no state.
 *
 * <p>The store participates in Raft snapshots for cross-node determinism. Only completed
 * entries are included, and all nodes see the same apply order → identical store contents.
 */
public class IdempotencyStore {

    private final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();

    /** Returns the journalId if this requestId already completed, null otherwise. */
    public String get(String requestId) {
        return store.get(requestId);
    }

    public void put(String requestId, String journalId) {
        store.put(requestId, journalId);
    }

    public boolean contains(String requestId) {
        return store.containsKey(requestId);
    }

    public void remove(String requestId) {
        store.remove(requestId);
    }

    public void clear() {
        store.clear();
    }

    public Map<String, String> getAll() {
        return Map.copyOf(store);
    }

    public int size() {
        return store.size();
    }
}

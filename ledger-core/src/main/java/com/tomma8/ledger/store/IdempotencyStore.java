package com.tomma8.ledger.store;

import com.tomma8.ledger.domain.model.IdempotencyEntry;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class IdempotencyStore {

    private final ConcurrentHashMap<String, IdempotencyEntry> store = new ConcurrentHashMap<>();

    public Optional<IdempotencyEntry> get(String requestId) {
        return Optional.ofNullable(store.get(requestId));
    }

    public void put(String requestId, IdempotencyEntry entry) {
        store.put(requestId, entry);
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

    public Map<String, IdempotencyEntry> getAll() {
        return Map.copyOf(store);
    }

    public int size() {
        return store.size();
    }
}

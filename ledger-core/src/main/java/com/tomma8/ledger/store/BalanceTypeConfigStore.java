package com.tomma8.ledger.store;

import com.tomma8.ledger.domain.model.BalanceTypeConfig;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class BalanceTypeConfigStore {

    private final ConcurrentHashMap<String, BalanceTypeConfig> store = new ConcurrentHashMap<>();

    public Optional<BalanceTypeConfig> get(String typeCode) {
        return Optional.ofNullable(store.get(typeCode));
    }

    public BalanceTypeConfig getOrThrow(String typeCode) {
        BalanceTypeConfig config = store.get(typeCode);
        if (config == null) {
            throw new IllegalStateException("Balance type config not found: " + typeCode);
        }
        return config;
    }

    public void put(String typeCode, BalanceTypeConfig config) {
        store.put(typeCode, config);
    }

    public void remove(String typeCode) {
        store.remove(typeCode);
    }

    public boolean contains(String typeCode) {
        return store.containsKey(typeCode);
    }

    public Map<String, BalanceTypeConfig> getAll() {
        return Map.copyOf(store);
    }

    public void clear() {
        store.clear();
    }

    public int size() {
        return store.size();
    }
}

package com.ibank.ledger.store;

import com.ibank.ledger.domain.model.AccountBalanceKey;
import com.ibank.ledger.domain.model.BalanceEntry;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class BalanceStore {

    private final ConcurrentHashMap<AccountBalanceKey, BalanceEntry> store = new ConcurrentHashMap<>();

    public Optional<BalanceEntry> get(AccountBalanceKey key) {
        return Optional.ofNullable(store.get(key));
    }

    public BalanceEntry getOrThrow(AccountBalanceKey key) {
        BalanceEntry entry = store.get(key);
        if (entry == null) {
            throw new IllegalStateException("Balance not found for key: " + key);
        }
        return entry;
    }

    public void put(AccountBalanceKey key, BalanceEntry entry) {
        store.put(key, entry);
    }

    public void initialize(AccountBalanceKey key) {
        store.putIfAbsent(key, BalanceEntry.zero());
    }

    public void remove(AccountBalanceKey key) {
        store.remove(key);
    }

    public Map<AccountBalanceKey, BalanceEntry> getAll() {
        return Map.copyOf(store);
    }

    public void clear() {
        store.clear();
    }

    public int size() {
        return store.size();
    }

    public boolean hasNonZeroBalance(String accountId) {
        return store.entrySet().stream()
                .filter(e -> e.getKey().accountId().equals(accountId))
                .anyMatch(e -> e.getValue().amount().compareTo(BigDecimal.ZERO) != 0);
    }
}

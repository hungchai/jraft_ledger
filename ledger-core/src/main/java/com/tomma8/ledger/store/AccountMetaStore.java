package com.tomma8.ledger.store;

import com.tomma8.ledger.domain.model.Account;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class AccountMetaStore {

    private final ConcurrentHashMap<String, Account> store = new ConcurrentHashMap<>();

    public Optional<Account> get(String accountId) {
        return Optional.ofNullable(store.get(accountId));
    }

    public Account getOrThrow(String accountId) {
        Account account = store.get(accountId);
        if (account == null) {
            throw new IllegalStateException("Account not found: " + accountId);
        }
        return account;
    }

    public void put(String accountId, Account account) {
        store.put(accountId, account);
    }

    public boolean contains(String accountId) {
        return store.containsKey(accountId);
    }

    public void clear() {
        store.clear();
    }

    public Map<String, Account> getAll() {
        return Map.copyOf(store);
    }

    public int size() {
        return store.size();
    }
}

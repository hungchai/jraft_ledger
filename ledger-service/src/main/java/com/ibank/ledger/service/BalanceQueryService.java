package com.ibank.ledger.service;

import com.ibank.ledger.domain.exception.AccountNotFoundException;
import com.ibank.ledger.domain.model.*;
import com.ibank.ledger.store.AccountMetaStore;
import com.ibank.ledger.store.BalanceStore;
import com.ibank.ledger.store.BalanceTypeConfigStore;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * F-005 Balance Query.
 * Reads directly from in-memory State Machine (strong consistency).
 */
public class BalanceQueryService {

    private final BalanceStore balanceStore;
    private final AccountMetaStore accountMetaStore;
    private final BalanceTypeConfigStore balanceTypeConfigStore;

    public BalanceQueryService(BalanceStore balanceStore,
                               AccountMetaStore accountMetaStore,
                               BalanceTypeConfigStore balanceTypeConfigStore) {
        this.balanceStore = balanceStore;
        this.accountMetaStore = accountMetaStore;
        this.balanceTypeConfigStore = balanceTypeConfigStore;
    }

    public BalanceQueryResult getBalance(String accountId, String balanceType, String currency) {
        if (!accountMetaStore.contains(accountId)) {
            throw new AccountNotFoundException(accountId);
        }

        AccountBalanceKey key = new AccountBalanceKey(accountId, balanceType, currency);
        BalanceEntry entry = balanceStore.get(key).orElse(BalanceEntry.zero());

        boolean allowNegative = balanceTypeConfigStore.get(balanceType)
                .map(BalanceTypeConfig::allowNegative)
                .orElse(false);

        return BalanceQueryResult.stateMachine(accountId, balanceType, currency, entry.amount(), allowNegative);
    }

    public List<BalanceQueryResult> getBatchBalances(List<AccountBalanceKey> keys) {
        List<BalanceQueryResult> results = new ArrayList<>();
        for (var key : keys) {
            boolean allowNegative = balanceTypeConfigStore.get(key.balanceType())
                    .map(BalanceTypeConfig::allowNegative)
                    .orElse(false);
            BalanceEntry entry = balanceStore.get(key).orElse(BalanceEntry.zero());
            results.add(BalanceQueryResult.stateMachine(
                    key.accountId(), key.balanceType(), key.currency(), entry.amount(), allowNegative));
        }
        return results;
    }

    public BalanceQueryResult getAsOfBalance(String accountId, String balanceType,
                                              String currency, LocalDate asOf) {
        // Snapshot-based historical query not yet implemented.
        // Falls back to current balance.
        return getBalance(accountId, balanceType, currency);
    }
}

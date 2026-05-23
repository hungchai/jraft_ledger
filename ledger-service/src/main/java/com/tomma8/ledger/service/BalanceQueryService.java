package com.tomma8.ledger.service;

import com.tomma8.ledger.domain.exception.AccountNotFoundException;
import com.tomma8.ledger.domain.model.*;
import com.tomma8.ledger.store.AccountMetaStore;
import com.tomma8.ledger.store.BalanceStore;
import com.tomma8.ledger.store.BalanceTypeConfigStore;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * F-005 Balance Query.
 * Reads directly from in-memory State Machine (strong consistency).
 */
public class BalanceQueryService {


    private final BalanceStore balanceStore;
    private final AccountMetaStore accountMetaStore;
    private final BalanceTypeConfigStore balanceTypeConfigStore;

    private static final List<String> ALL_POSITIONS = List.of("CURRENT", "LOCKED", "FROZEN");

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

        boolean allowNegative = balanceTypeConfigStore.get(balanceType)
                .map(BalanceTypeConfig::allowNegative)
                .orElse(false);

        // Aggregate all positions
        Map<String, BigDecimal> positions = new HashMap<>();
        for (String position : ALL_POSITIONS) {
            AccountBalanceKey key = new AccountBalanceKey(accountId, balanceType, position, currency);
            BigDecimal amount = balanceStore.get(key).map(BalanceEntry::amount).orElse(BigDecimal.ZERO);
            if (amount.compareTo(BigDecimal.ZERO) != 0) {
                positions.put(position, amount);
            }
        }
        if (positions.isEmpty()) {
            positions.put("CURRENT", BigDecimal.ZERO);
        }

        return BalanceQueryResult.aggregated(accountId, balanceType, currency, positions, allowNegative);
    }

    public BalanceQueryResult getBalanceByPosition(String accountId, String balanceType,
                                                    String position, String currency) {
        if (!accountMetaStore.contains(accountId)) {
            throw new AccountNotFoundException(accountId);
        }

        boolean allowNegative = balanceTypeConfigStore.get(balanceType)
                .map(BalanceTypeConfig::allowNegative)
                .orElse(false);

        AccountBalanceKey key = new AccountBalanceKey(accountId, balanceType, position, currency);
        BalanceEntry entry = balanceStore.get(key).orElse(BalanceEntry.zero());

        return BalanceQueryResult.single(accountId, balanceType, position, currency, entry.amount(), allowNegative);
    }

    public List<BalanceQueryResult> getBatchBalances(List<AccountBalanceKey> keys) {
        List<BalanceQueryResult> results = new ArrayList<>();
        for (var key : keys) {
            boolean allowNegative = balanceTypeConfigStore.get(key.balanceType())
                    .map(BalanceTypeConfig::allowNegative)
                    .orElse(false);
            BalanceEntry entry = balanceStore.get(key).orElse(BalanceEntry.zero());
            results.add(BalanceQueryResult.single(
                    key.accountId(), key.balanceType(), key.position(), key.currency(),
                    entry.amount(), allowNegative));
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

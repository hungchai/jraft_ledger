package com.tomma8.ledger.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public record Account(
        String accountId,
        AccountType accountType,
        String displayName,
        String ownerId,
        AccountStatus status,
        Set<String> allowedBalanceTypes,
        Instant createdAt) {

    public Account {
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(accountType, "accountType must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public Account withStatus(AccountStatus newStatus) {
        return new Account(accountId, accountType, displayName, ownerId, newStatus, allowedBalanceTypes, createdAt);
    }

    public Account withAdditionalBalanceType(String balanceType) {
        var updated = new java.util.HashSet<>(allowedBalanceTypes != null ? allowedBalanceTypes : Set.of());
        updated.add(balanceType);
        return new Account(accountId, accountType, displayName, ownerId, status, Set.copyOf(updated), createdAt);
    }
}

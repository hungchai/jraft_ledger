package com.tomma8.ledger.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record JournalLine(
        String journalLineId,
        String journalId,
        String legId,
        String accountId,
        String balanceType,
        String currency,
        EntryType entryType,
        BigDecimal amount,
        BigDecimal balanceBefore,
        BigDecimal balanceAfter,
        int configVersion,
        Instant createdAt) {

    public JournalLine {
        Objects.requireNonNull(journalLineId, "journalLineId must not be null");
        Objects.requireNonNull(journalId, "journalId must not be null");
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(balanceType, "balanceType must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(entryType, "entryType must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(balanceBefore, "balanceBefore must not be null");
        Objects.requireNonNull(balanceAfter, "balanceAfter must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }
}

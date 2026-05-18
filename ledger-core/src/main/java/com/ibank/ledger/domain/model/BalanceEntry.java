package com.ibank.ledger.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record BalanceEntry(
        BigDecimal amount,
        long stateVersion,
        long accountSeq,
        String lastJournalId,
        Instant lastUpdatedAt) {

    public BalanceEntry {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(lastJournalId, "lastJournalId must not be null");
        Objects.requireNonNull(lastUpdatedAt, "lastUpdatedAt must not be null");
        if (accountSeq < 0) {
            throw new IllegalArgumentException("accountSeq must be >= 0");
        }
    }

    public static BalanceEntry zero() {
        return new BalanceEntry(BigDecimal.ZERO, 0, 0, "", Instant.EPOCH);
    }
}

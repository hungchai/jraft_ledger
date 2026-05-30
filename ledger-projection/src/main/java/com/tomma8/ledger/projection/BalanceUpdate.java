package com.tomma8.ledger.projection;

import java.math.BigDecimal;

/**
 * A balance update to be applied to MySQL via the {@link ConflationQueue}.
 * Keyed by (accountId, balanceType, currency) — only the highest accountSeq wins.
 */
public record BalanceUpdate(
        long accountPk,
        String accountId,
        String balanceType,
        String currency,
        BigDecimal amount,
        String position,
        long accountSeq,
        String lastJournalId) {

    public String key() {
        return accountId + ":" + balanceType + ":" + currency;
    }
}

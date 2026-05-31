package com.tomma8.ledger.projection;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A balance update to be applied to MySQL via the {@link ConflationQueue}.
 * Keyed by (accountId, balanceType, currency) — only the highest accountSeq wins.
 *
 * equals/hashCode use only the key fields so ConflationQueue can conflate
 * by replacing entries in a HashMap.
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BalanceUpdate that)) return false;
        return Objects.equals(accountId, that.accountId)
                && Objects.equals(balanceType, that.balanceType)
                && Objects.equals(currency, that.currency);
    }

    /**
     * Hash over the key fields only. Manual 31-multiplier form avoids the
     * {@code Object[]} varargs array that {@link Objects#hash} allocates on
     * every call — this runs on the ConflationQueue hot path.
     */
    @Override
    public int hashCode() {
        int h = accountId == null ? 0 : accountId.hashCode();
        h = 31 * h + (balanceType == null ? 0 : balanceType.hashCode());
        h = 31 * h + (currency == null ? 0 : currency.hashCode());
        return h;
    }
}

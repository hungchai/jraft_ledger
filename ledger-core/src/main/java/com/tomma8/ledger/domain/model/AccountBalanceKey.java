package com.tomma8.ledger.domain.model;

import java.util.Objects;

public record AccountBalanceKey(String accountId, String balanceType, String position, String currency) {

    public AccountBalanceKey {
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(balanceType, "balanceType must not be null");
        Objects.requireNonNull(position, "position must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
    }
}

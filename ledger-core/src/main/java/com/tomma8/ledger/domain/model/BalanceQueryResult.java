package com.tomma8.ledger.domain.model;

import java.math.BigDecimal;

public record BalanceQueryResult(
        String accountId,
        String balanceType,
        String currency,
        BigDecimal amount,
        boolean allowNegative,
        String dataSource) {

    public static BalanceQueryResult stateMachine(String accountId, String balanceType,
                                                   String currency, BigDecimal amount, boolean allowNegative) {
        return new BalanceQueryResult(accountId, balanceType, currency, amount, allowNegative, "STATE_MACHINE");
    }
}

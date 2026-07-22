package com.tomma8.ledger.domain.model;

import java.math.BigDecimal;
import java.util.Map;

public record BalanceQueryResult(
        String accountId,
        String balanceType,
        String currency,
        BigDecimal amount,
        Map<String, BigDecimal> positions,
        boolean allowNegative,
        String dataSource,
        String accountType) {

    public static BalanceQueryResult single(String accountId, String balanceType,
                                             String position, String currency,
                                             BigDecimal amount, boolean allowNegative,
                                             String accountType) {
        return new BalanceQueryResult(accountId, balanceType, currency, amount,
                Map.of(position, amount), allowNegative, "STATE_MACHINE", accountType);
    }

    public static BalanceQueryResult aggregated(String accountId, String balanceType,
                                                 String currency,
                                                 Map<String, BigDecimal> positions,
                                                 boolean allowNegative,
                                                 String accountType) {
        BigDecimal total = positions.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new BalanceQueryResult(accountId, balanceType, currency, total,
                positions, allowNegative, "STATE_MACHINE", accountType);
    }
}

package com.tomma8.ledger.domain.command;

import java.util.Objects;

public record AccountAddBalanceTypeCommand(String requestId, String accountId,
                                           String balanceType, String currency) implements RaftCommand {
    public AccountAddBalanceTypeCommand {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(balanceType, "balanceType must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
    }
}

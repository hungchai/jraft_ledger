package com.tomma8.ledger.domain.command;

import com.tomma8.ledger.domain.model.AccountType;

import java.util.List;
import java.util.Objects;

public record AccountCreateCommand(
        String requestId,
        String accountId,
        AccountType accountType,
        String displayName,
        String ownerId,
        List<BalanceInitialization> balanceInitializations) implements RaftCommand {

    public record BalanceInitialization(String balanceType, String currency) {
        public BalanceInitialization {
            Objects.requireNonNull(balanceType, "balanceType must not be null");
            Objects.requireNonNull(currency, "currency must not be null");
        }
    }

    public AccountCreateCommand {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(accountType, "accountType must not be null");
        Objects.requireNonNull(balanceInitializations, "balanceInitializations must not be null");
    }
}

package com.tomma8.ledger.domain.command;

import java.util.Objects;

public record AccountCloseCommand(String requestId, String accountId) implements RaftCommand {
    public AccountCloseCommand {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(accountId, "accountId must not be null");
    }
}

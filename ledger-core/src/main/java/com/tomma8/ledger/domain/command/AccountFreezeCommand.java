package com.tomma8.ledger.domain.command;

import java.util.Objects;

public record AccountFreezeCommand(String requestId, String accountId, boolean freeze) implements RaftCommand {
    public AccountFreezeCommand {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(accountId, "accountId must not be null");
    }
}

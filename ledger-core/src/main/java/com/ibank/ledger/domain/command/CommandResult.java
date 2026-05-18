package com.ibank.ledger.domain.command;

import java.util.List;
import java.util.Objects;

public record CommandResult(
        String status,
        String journalId,
        List<String> errorCodes) {

    public static final String COMPLETED = "COMPLETED";
    public static final String REJECTED = "REJECTED";

    public CommandResult {
        Objects.requireNonNull(status, "status must not be null");
    }

    public static CommandResult completed(String journalId) {
        return new CommandResult(COMPLETED, journalId, List.of());
    }

    public static CommandResult rejected(List<String> errorCodes) {
        return new CommandResult(REJECTED, null, List.copyOf(errorCodes));
    }

    public static CommandResult rejected(String errorCode) {
        return rejected(List.of(errorCode));
    }

    public boolean isCompleted() {
        return COMPLETED.equals(status);
    }

    public boolean isRejected() {
        return REJECTED.equals(status);
    }
}

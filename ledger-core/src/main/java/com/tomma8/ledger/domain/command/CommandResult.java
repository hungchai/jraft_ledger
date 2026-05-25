package com.tomma8.ledger.domain.command;

import com.tomma8.ledger.domain.model.LedgerErrorCode;

import java.util.List;
import java.util.Objects;

/**
 * Result of a command applied to the State Machine.
 * Immutable record containing status, journalId, and error codes.
 */
public record CommandResult(
        String status,
        String journalId,
        List<LedgerErrorCode> errorCodes) {

    public static final String COMPLETED = "COMPLETED";
    public static final String REJECTED = "REJECTED";

    public CommandResult {
        Objects.requireNonNull(status, "status must not be null");
    }

    public static CommandResult completed(String journalId) {
        return new CommandResult(COMPLETED, journalId, List.of());
    }

    public static CommandResult rejected(List<LedgerErrorCode> errorCodes) {
        return new CommandResult(REJECTED, null, List.copyOf(errorCodes));
    }

    public static CommandResult rejected(LedgerErrorCode errorCode) {
        return rejected(List.of(errorCode));
    }

    public boolean isCompleted() {
        return COMPLETED.equals(status);
    }

    public boolean isRejected() {
        return REJECTED.equals(status);
    }
}

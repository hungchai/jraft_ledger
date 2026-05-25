package com.tomma8.ledger.domain.model;

import com.tomma8.ledger.domain.command.CommandResult;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record IdempotencyEntry(
        String requestId,
        String status,
        String journalId,
        List<LedgerErrorCode> errors,
        Instant completedAt) {

    public IdempotencyEntry {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(completedAt, "completedAt must not be null");
    }

    public static IdempotencyEntry completed(String requestId, String journalId, Instant at) {
        return new IdempotencyEntry(requestId, CommandResult.COMPLETED, journalId, List.of(), at);
    }

    public static IdempotencyEntry rejected(String requestId, List<LedgerErrorCode> errors, Instant at) {
        return new IdempotencyEntry(requestId, CommandResult.REJECTED, null, List.copyOf(errors), at);
    }
}

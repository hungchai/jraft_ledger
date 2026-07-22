package com.tomma8.ledger.domain.command;

import java.time.LocalDate;
import java.util.Objects;

public record ReversalCommand(
        String requestId,
        String originalJournalId,
        String reversalReason,
        String reversalReasonCode,
        LocalDate valueDate) implements RaftCommand {

    public ReversalCommand {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(originalJournalId, "originalJournalId must not be null");
        Objects.requireNonNull(reversalReason, "reversalReason must not be null");
        Objects.requireNonNull(reversalReasonCode, "reversalReasonCode must not be null");
        Objects.requireNonNull(valueDate, "valueDate must not be null");
    }
}
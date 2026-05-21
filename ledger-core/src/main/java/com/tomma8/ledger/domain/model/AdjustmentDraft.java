package com.tomma8.ledger.domain.model;

import com.tomma8.ledger.domain.command.PostingCommand;

import java.time.Instant;
import java.util.Objects;

public record AdjustmentDraft(
        String draftId,
        PostingCommand command,
        String makerId,
        DraftStatus status,
        Instant createdAt,
        Instant expiresAt) {

    public AdjustmentDraft {
        Objects.requireNonNull(draftId, "draftId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(makerId, "makerId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }

    public AdjustmentDraft approve() {
        return new AdjustmentDraft(draftId, command, makerId, DraftStatus.EXECUTED, createdAt, expiresAt);
    }

    public AdjustmentDraft reject() {
        return new AdjustmentDraft(draftId, command, makerId, DraftStatus.REJECTED, createdAt, expiresAt);
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}

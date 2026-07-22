package com.tomma8.ledger.domain.command;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public record AdjustmentCommand(
        String requestId,
        String businessEventType,
        String businessEventRef,
        LocalDate valueDate,
        List<PostingCommand.Leg> legs,
        String adjustmentType,
        String draftId) implements RaftCommand {

    public AdjustmentCommand {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(legs, "legs must not be null");
        Objects.requireNonNull(adjustmentType, "adjustmentType must not be null");
    }
}

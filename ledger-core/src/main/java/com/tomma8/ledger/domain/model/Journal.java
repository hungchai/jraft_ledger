package com.tomma8.ledger.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public record Journal(
        String journalId,
        JournalType journalType,
        String requestId,
        String businessEventType,
        String businessEventRef,
        LocalDate valueDate,
        JournalStatus status,
        List<JournalLine> lines,
        boolean crossPeriod,
        Instant createdAt) {

    public Journal {
        Objects.requireNonNull(journalId, "journalId must not be null");
        Objects.requireNonNull(journalType, "journalType must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public Journal withStatus(JournalStatus newStatus) {
        return new Journal(journalId, journalType, requestId, businessEventType, businessEventRef,
                valueDate, newStatus, lines, crossPeriod, createdAt);
    }
}

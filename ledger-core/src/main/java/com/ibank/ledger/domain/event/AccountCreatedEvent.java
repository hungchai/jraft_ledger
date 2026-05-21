package com.ibank.ledger.domain.event;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public record AccountCreatedEvent(
        String eventId,
        String eventType,
        String eventVersion,
        Instant occurredAt,
        String accountId,
        String accountType,
        String displayName,
        String ownerId,
        String status,
        Set<String> balanceTypes,
        Instant createdAt) {

    public static final String EVENT_TYPE = "ACCOUNT_CREATED";
    public static final String EVENT_VERSION = "1.0";

    public AccountCreatedEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(accountType, "accountType must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }
}

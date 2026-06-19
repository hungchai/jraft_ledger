package com.tomma8.ledger.domain.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Lightweight idempotency record: only completed (successful) requestIds are stored.
 * Failed/rejected requests are NOT cached — a retry re-evaluates against current state,
 * which may have changed (e.g. balance became sufficient, account was created).
 *
 * <p>Memory: bounded by successful requests only (~1/7 of previous unbounded store).
 */
public record IdempotencyEntry(
        String requestId,
        String journalId) {

    public IdempotencyEntry {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(journalId, "journalId must not be null");
    }

    @JsonCreator
    public static IdempotencyEntry fromJson(
            @JsonProperty("requestId") String requestId,
            @JsonProperty("journalId") String journalId) {
        return new IdempotencyEntry(
                requestId != null ? requestId : "",
                journalId != null ? journalId : "");
    }

    public static IdempotencyEntry completed(String requestId, String journalId) {
        return new IdempotencyEntry(requestId, journalId);
    }
}

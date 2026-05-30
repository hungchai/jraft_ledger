package com.tomma8.ledger.domain.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.tomma8.ledger.domain.command.CommandResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record IdempotencyEntry(
        String requestId,
        String status,
        String journalId,
        List<LedgerErrorCode> errors,
        Map<String, String> errorDetails,
        Instant completedAt) {

    public IdempotencyEntry {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(errors, "errors must not be null");
        Objects.requireNonNull(errorDetails, "errorDetails must not be null");
        Objects.requireNonNull(completedAt, "completedAt must not be null");
    }

    @JsonCreator
    public static IdempotencyEntry fromJson(
            @JsonProperty("requestId") String requestId,
            @JsonProperty("status") String status,
            @JsonProperty("journalId") String journalId,
            @JsonProperty("errors") List<LedgerErrorCode> errors,
            @JsonProperty("errorDetails") Map<String, String> errorDetails,
            @JsonProperty("completedAt") Instant completedAt) {
        return new IdempotencyEntry(
                requestId != null ? requestId : "",
                status != null ? status : CommandResult.REJECTED,
                journalId,
                errors != null ? errors : List.of(),
                errorDetails != null ? errorDetails : Map.of(),
                completedAt != null ? completedAt : Instant.EPOCH);
    }

    public static IdempotencyEntry completed(String requestId, String journalId, Instant at) {
        return new IdempotencyEntry(requestId, CommandResult.COMPLETED, journalId, List.of(), Map.of(), at);
    }

    public static IdempotencyEntry rejected(String requestId, List<LedgerErrorCode> errors, Instant at) {
        return rejected(requestId, errors, Map.of(), at);
    }

    public static IdempotencyEntry rejected(String requestId, List<LedgerErrorCode> errors, Map<String, String> errorDetails, Instant at) {
        return new IdempotencyEntry(requestId, CommandResult.REJECTED, null, List.copyOf(errors), Map.copyOf(errorDetails), at);
    }
}

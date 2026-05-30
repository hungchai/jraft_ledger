package com.tomma8.ledger.domain.command;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.tomma8.ledger.domain.model.LedgerErrorCode;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Result of a command applied to the State Machine.
 * Immutable record containing status, journalId, error codes, and optional error details.
 */
public record CommandResult(
        String status,
        String journalId,
        List<LedgerErrorCode> errorCodes,
        Map<String, String> errorDetails) {

    public static final String COMPLETED = "COMPLETED";
    public static final String REJECTED = "REJECTED";

    public CommandResult {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(errorCodes, "errorCodes must not be null");
        Objects.requireNonNull(errorDetails, "errorDetails must not be null");
    }

    @JsonCreator
    public static CommandResult fromJson(
            @JsonProperty("status") String status,
            @JsonProperty("journalId") String journalId,
            @JsonProperty("errorCodes") List<LedgerErrorCode> errorCodes,
            @JsonProperty("errorDetails") Map<String, String> errorDetails) {
        return new CommandResult(
                status != null ? status : REJECTED,
                journalId,
                errorCodes != null ? errorCodes : List.of(),
                errorDetails != null ? errorDetails : Map.of());
    }

    public static CommandResult completed(String journalId) {
        return new CommandResult(COMPLETED, journalId, List.of(), Map.of());
    }

    public static CommandResult rejected(List<LedgerErrorCode> errorCodes) {
        return rejected(errorCodes, Map.of());
    }

    public static CommandResult rejected(List<LedgerErrorCode> errorCodes, Map<String, String> errorDetails) {
        return new CommandResult(REJECTED, null, List.copyOf(errorCodes), Map.copyOf(errorDetails));
    }

    public static CommandResult rejected(LedgerErrorCode errorCode) {
        return rejected(List.of(errorCode), Map.of());
    }

    public static CommandResult rejected(LedgerErrorCode errorCode, Map<String, String> errorDetails) {
        return rejected(List.of(errorCode), errorDetails);
    }

    public boolean isCompleted() {
        return COMPLETED.equals(status);
    }

    public boolean isRejected() {
        return REJECTED.equals(status);
    }
}

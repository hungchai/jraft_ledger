package com.ibank.ledger.domain.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * F-011b Posting Completion Event — emitted per requestId.
 */
public record PostingCompletionEvent(
        String eventId,
        String eventType,
        String eventVersion,
        Instant occurredAt,
        String idempotencyKey,
        String requestId,
        String commandType,
        String businessEventType,
        String businessEventRef,
        String result,
        String journalId,
        Instant bookedAt,
        Long raftLogIndex,
        List<LegResult> legs,
        List<ErrorDetail> errors,
        String traceId,
        Map<String, String> metadata) {

    public static final String EVENT_TYPE = "POSTING_COMPLETION";
    public static final String EVENT_VERSION = "1.0";

    public record LegResult(String legId, String postingType, List<LineResult> lines) {}

    public record LineResult(
            String journalLineId, String accountId, String balanceType, String currency,
            String entryType, BigDecimal amount,
            BigDecimal balanceBefore, BigDecimal balanceAfter) {}

    public record ErrorDetail(
            String errorCode, String accountId, String balanceType, String currency,
            BigDecimal required, BigDecimal available) {}
}

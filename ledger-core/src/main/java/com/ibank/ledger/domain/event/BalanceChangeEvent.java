package com.ibank.ledger.domain.event;

import com.ibank.ledger.domain.model.EntryType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

/**
 * F-011 Balance Change Event — emitted per JournalLine per balance change.
 * eventVersion 1.1 (v0.2) adds accountSeq / prevAccountSeq.
 */
public record BalanceChangeEvent(
        String eventId,
        String eventType,
        String eventVersion,
        Instant occurredAt,
        String idempotencyKey,
        String commandType,
        String journalId,
        String journalLineId,
        String requestId,
        String businessEventRef,
        String traceId,
        String accountId,
        String balanceType,
        String currency,
        EntryType entryType,
        BigDecimal amount,
        BigDecimal preBalance,
        BigDecimal postBalance,
        BigDecimal balanceDelta,
        long raftLogIndex,
        long stateVersion,
        long accountSeq,
        long prevAccountSeq,
        LocalDate accountingDate,
        LocalDate valueDate,
        Map<String, String> metadata) {

    public static final String EVENT_TYPE = "BALANCE_CHANGE";
    public static final String EVENT_VERSION = "1.1";

    public BalanceChangeEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(balanceType, "balanceType must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(preBalance, "preBalance must not be null");
        Objects.requireNonNull(postBalance, "postBalance must not be null");
        // Negative accountSeq = 0 for first event (prevAccountSeq)
        if (accountSeq < 1) {
            throw new IllegalArgumentException("accountSeq must be >= 1");
        }
    }
}

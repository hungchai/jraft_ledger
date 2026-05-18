package com.ibank.ledger.domain.command;

import com.ibank.ledger.domain.model.EntryType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public record PostingCommand(
        String requestId,
        String businessEventType,
        String businessEventRef,
        LocalDate valueDate,
        List<Leg> legs) implements RaftCommand {

    public record Leg(String legId, String postingType, List<Line> lines) {
        public Leg {
            Objects.requireNonNull(legId, "legId must not be null");
            Objects.requireNonNull(postingType, "postingType must not be null");
            Objects.requireNonNull(lines, "lines must not be null");
        }
    }

    public record Line(String accountId, String balanceType, String currency,
                       EntryType entryType, BigDecimal amount, String description) {
        public Line {
            Objects.requireNonNull(accountId, "accountId must not be null");
            Objects.requireNonNull(balanceType, "balanceType must not be null");
            Objects.requireNonNull(currency, "currency must not be null");
            Objects.requireNonNull(entryType, "entryType must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("amount must be positive");
            }
        }
    }

    public PostingCommand {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(legs, "legs must not be null");
    }
}

package com.ibank.ledger.domain.model;

import java.time.LocalDate;

public record AccountingPeriod(
        String periodId,
        LocalDate date,
        PeriodStatus status) {

    public AccountingPeriod withStatus(PeriodStatus newStatus) {
        return new AccountingPeriod(periodId, date, newStatus);
    }
}

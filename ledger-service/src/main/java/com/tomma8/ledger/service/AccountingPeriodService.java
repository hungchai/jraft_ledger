package com.tomma8.ledger.service;

import com.tomma8.ledger.domain.model.AccountingPeriod;
import com.tomma8.ledger.domain.model.PeriodStatus;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * F-009 Accounting Period / EOD.
 */
public class AccountingPeriodService {

    private final ConcurrentSkipListMap<LocalDate, AccountingPeriod> periods = new ConcurrentSkipListMap<>();

    public AccountingPeriod getPeriod(LocalDate date) {
        return periods.computeIfAbsent(date,
                d -> new AccountingPeriod("PERIOD-" + d, d, PeriodStatus.OPEN));
    }

    public AccountingPeriod getPeriodOrNull(LocalDate date) {
        return periods.get(date);
    }

    public void triggerEOD(LocalDate date) {
        AccountingPeriod period = getPeriod(date);

        // Step 1: Mark as CLOSING
        periods.put(date, period.withStatus(PeriodStatus.CLOSING));

        // Step 2: Snapshot (placeholder)
        // Step 3: Reconciliation (placeholder)
        // Step 4: Snapshot (placeholder)
        // Step 5: Report (placeholder)

        // Step 6: Mark as CLOSED
        periods.put(date, period.withStatus(PeriodStatus.CLOSED));
    }

    public boolean isClosed(LocalDate date) {
        AccountingPeriod period = periods.get(date);
        return period != null && period.status() == PeriodStatus.CLOSED;
    }

    public boolean isClosedOrClosing(LocalDate date) {
        AccountingPeriod period = periods.get(date);
        return period != null &&
                (period.status() == PeriodStatus.CLOSED || period.status() == PeriodStatus.CLOSING);
    }
}

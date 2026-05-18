package com.ibank.ledger.service;

import com.ibank.ledger.domain.model.*;
import com.ibank.ledger.statemachine.LedgerStateMachine;

import java.math.BigDecimal;
import java.util.*;

/**
 * F-007 Reconciliation.
 * L1: Journal balance integrity
 * L2: Control account sub-totaling
 * L3: External file matching
 */
public class ReconciliationService {

    private final LedgerStateMachine stateMachine;

    public ReconciliationService(LedgerStateMachine stateMachine) {
        this.stateMachine = stateMachine;
    }

    public ReconciliationReport runL1Reconciliation(String date, List<Journal> journals) {
        ReconciliationReport report = new ReconciliationReport(date);
        int unbalanced = 0;

        for (Journal journal : journals) {
            BigDecimal debitTotal = BigDecimal.ZERO;
            BigDecimal creditTotal = BigDecimal.ZERO;
            for (var line : journal.lines()) {
                if (line.entryType() == EntryType.DEBIT) {
                    debitTotal = debitTotal.add(line.amount());
                } else {
                    creditTotal = creditTotal.add(line.amount());
                }
            }
            if (debitTotal.compareTo(creditTotal) != 0) {
                unbalanced++;
                report.addCase(new ReconciliationReport.Case(
                        "CASE-" + journal.journalId(),
                        "L1_UNBALANCED",
                        null,
                        debitTotal.subtract(creditTotal).abs(),
                        "Unbalanced journal: " + journal.journalId()));
            }
        }

        report.setL1Summary(unbalanced == 0
                ? ReconciliationReport.L1Summary.ok(journals.size())
                : ReconciliationReport.L1Summary.failed(journals.size(), unbalanced));
        return report;
    }

    public ReconciliationReport runL2Reconciliation(String date,
                                                     Map<String, BigDecimal> accountBalances,
                                                     String controlAccountId,
                                                     BigDecimal controlBalance,
                                                     BigDecimal tolerance) {
        ReconciliationReport report = new ReconciliationReport(date);

        BigDecimal subAccountsSum = accountBalances.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal diff = subAccountsSum.subtract(controlBalance).abs();

        if (diff.compareTo(tolerance) <= 0) {
            report.setL2Summary(new ReconciliationReport.L2Summary(1, 0));
        } else {
            report.setL2Summary(new ReconciliationReport.L2Summary(0, 1));
            report.addCase(new ReconciliationReport.Case(
                    "CASE-L2-001", "L2_CONTROL_MISMATCH",
                    controlAccountId, diff,
                    "Control mismatch: sum=" + subAccountsSum + " expected=" + controlBalance));
        }
        return report;
    }

    public ReconciliationReport runL3Reconciliation(String date,
                                                     List<ExternalRecord> externalRecords,
                                                     List<Journal> internalJournals) {
        ReconciliationReport report = new ReconciliationReport(date);

        Set<String> internalKeys = new HashSet<>();
        Map<String, Journal> internalMap = new HashMap<>();
        for (var j : internalJournals) {
            internalKeys.add(j.businessEventRef());
            internalMap.put(j.businessEventRef(), j);
        }

        Map<String, ExternalRecord> externalMap = new HashMap<>();
        for (var r : externalRecords) {
            externalMap.put(r.externalRef(), r);
        }

        int matched = 0, internalOnly = 0, externalOnly = 0, amountMismatch = 0;

        for (var ext : externalRecords) {
            Journal internal = internalMap.get(ext.externalRef());
            if (internal == null) {
                externalOnly++;
                report.addCase(new ReconciliationReport.Case(
                        "CASE-L3-EXT-" + ext.externalRef(), "EXTERNAL_ONLY", null, ext.amount(),
                        "External record has no internal match: " + ext.externalRef()));
                continue;
            }
            // Compare total amount per leg's credit side
            BigDecimal internalAmount = internal.lines().stream()
                    .filter(l -> l.entryType() == EntryType.CREDIT)
                    .map(JournalLine::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (internalAmount.compareTo(ext.amount()) != 0) {
                amountMismatch++;
                report.addCase(new ReconciliationReport.Case(
                        "CASE-L3-MISMATCH-" + ext.externalRef(), "AMOUNT_MISMATCH", null,
                        internalAmount.subtract(ext.amount()).abs(),
                        "Amount mismatch for " + ext.externalRef()));
            } else {
                matched++;
            }
        }

        for (var j : internalJournals) {
            if (!externalMap.containsKey(j.businessEventRef())) {
                internalOnly++;
                report.addCase(new ReconciliationReport.Case(
                        "CASE-L3-INT-" + j.journalId(), "INTERNAL_ONLY", null, BigDecimal.ZERO,
                        "Internal journal has no external match: " + j.journalId()));
            }
        }

        report.setL3Summary(new ReconciliationReport.L3Summary(
                matched, internalOnly, externalOnly, amountMismatch));
        return report;
    }

    public record ExternalRecord(String externalRef, BigDecimal amount) {}
}

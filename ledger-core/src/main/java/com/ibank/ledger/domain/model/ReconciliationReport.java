package com.ibank.ledger.domain.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class ReconciliationReport {

    public record L1Summary(int totalJournals, int unbalancedJournals, boolean balanceConsistencyPassed) {
        public static L1Summary ok(int total) {
            return new L1Summary(total, 0, true);
        }
        public static L1Summary failed(int total, int unbalanced) {
            return new L1Summary(total, unbalanced, false);
        }
    }

    public record L2Summary(int rulesPassed, int rulesFailed) {}

    public record L3Summary(int matched, int internalOnly, int externalOnly, int amountMismatch) {}

    public record Case(String caseId, String type, String accountId, BigDecimal discrepancy, String description) {}

    private final String date;
    private L1Summary l1Summary;
    private L2Summary l2Summary;
    private L3Summary l3Summary;
    private final List<Case> cases = new ArrayList<>();

    public ReconciliationReport(String date) {
        this.date = date;
    }

    public String date() { return date; }
    public L1Summary l1Summary() { return l1Summary; }
    public L2Summary l2Summary() { return l2Summary; }
    public L3Summary l3Summary() { return l3Summary; }
    public List<Case> cases() { return List.copyOf(cases); }

    public void setL1Summary(L1Summary s) { this.l1Summary = s; }
    public void setL2Summary(L2Summary s) { this.l2Summary = s; }
    public void setL3Summary(L3Summary s) { this.l3Summary = s; }

    public void addCase(Case c) { cases.add(c); }
}

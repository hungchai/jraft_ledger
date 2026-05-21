package com.tomma8.ledger.service;

import com.tomma8.ledger.domain.command.CommandResult;
import com.tomma8.ledger.domain.command.PostingCommand;
import com.tomma8.ledger.domain.model.*;
import com.tomma8.ledger.statemachine.LedgerStateMachine;
import com.tomma8.ledger.store.AccountMetaStore;
import com.tomma8.ledger.store.BalanceStore;
import com.tomma8.ledger.store.BalanceTypeConfigStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("ReconciliationService (F-007)")
class ReconciliationServiceTest {

    private BalanceStore balanceStore;
    private AccountMetaStore accountMetaStore;
    private BalanceTypeConfigStore balanceTypeConfigStore;
    private LedgerStateMachine stateMachine;
    private ReconciliationService reconciliationService;

    @BeforeEach
    void setUp() {
        balanceStore = new BalanceStore();
        accountMetaStore = new AccountMetaStore();
        balanceTypeConfigStore = new BalanceTypeConfigStore();
        stateMachine = new LedgerStateMachine(balanceStore, accountMetaStore, balanceTypeConfigStore);
        reconciliationService = new ReconciliationService(stateMachine);

        balanceTypeConfigStore.put("AVAILABLE_BALANCE", new BalanceTypeConfig(
                "AVAILABLE_BALANCE", false, null, SignConvention.NORMAL_CREDIT, 1));

        accountMetaStore.put("CLIENT_ACC_001", new Account(
                "CLIENT_ACC_001", AccountType.CLIENT, "C1",
                "CUST-001", AccountStatus.ACTIVE, null, Instant.now()));
        accountMetaStore.put("COMPANY_FX_ACC", new Account(
                "COMPANY_FX_ACC", AccountType.COMPANY, "Co",
                null, AccountStatus.ACTIVE, null, Instant.now()));
    }

    private void setBalance(String a, String t, String c, BigDecimal amt) {
        balanceStore.put(new AccountBalanceKey(a, t, c),
                new BalanceEntry(amt, 0, 1, "", Instant.now()));
    }

    private PostingCommand balancedPosting(String reqId, BigDecimal amount) {
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("100000.00"));
        setBalance("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD", new BigDecimal("100000.00"));
        return new PostingCommand(
                reqId, "TEST", "ref-" + reqId, LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.DEBIT, amount, "Debit"),
                        new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD",
                                EntryType.CREDIT, amount, "Credit")
                )))
        );
    }

    @Test
    @DisplayName("TC-F007-01 runL1Reconciliation all journals balanced no discrepancies")
    void runL1Reconciliation_allJournalsBalanced_noDiscrepancies() {
        List<Journal> journals = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            CommandResult r = stateMachine.applyPosting(balancedPosting("req-" + i, BigDecimal.ONE));
            journals.add(stateMachine.getJournal(r.journalId()));
        }

        ReconciliationReport report = reconciliationService.runL1Reconciliation("2026-05-17", journals);

        assertThat(report.l1Summary().unbalancedJournals()).isEqualTo(0);
        assertThat(report.l1Summary().totalJournals()).isEqualTo(100);
    }

    @Test
    @DisplayName("TC-F007-02 runL1Reconciliation unbalanced journal discrepancy detected")
    void runL1Reconciliation_unbalancedJournal_discrepancyDetected() {
        // Create an unbalanced journal manually
        Journal unbalanced = new Journal(
                "JNL-BAD", JournalType.NORMAL, "req-bad", "TEST", "ref-bad",
                LocalDate.now(), JournalStatus.CONFIRMED,
                List.of(
                        new JournalLine("JLL-1", "JNL-BAD", "leg-1", "CLIENT_ACC_001",
                                "AVAILABLE_BALANCE", "USD", EntryType.DEBIT, new BigDecimal("100.00"),
                                BigDecimal.ZERO, BigDecimal.ZERO, 1, Instant.now()),
                        new JournalLine("JLL-2", "JNL-BAD", "leg-1", "COMPANY_FX_ACC",
                                "AVAILABLE_BALANCE", "USD", EntryType.CREDIT, new BigDecimal("99.00"),
                                BigDecimal.ZERO, BigDecimal.ZERO, 1, Instant.now())
                ),
                false, Instant.now());

        ReconciliationReport report = reconciliationService.runL1Reconciliation("2026-05-17",
                List.of(unbalanced));

        assertThat(report.l1Summary().unbalancedJournals()).isEqualTo(1);
        assertThat(report.cases()).hasSize(1);
    }

    @Test
    @DisplayName("TC-F007-03 runL1Reconciliation balance mismatch detected and case created")
    void runL1Reconciliation_balanceMismatch_detectedAndCaseCreated() {
        Journal unbalanced = new Journal(
                "JNL-MISMATCH", JournalType.NORMAL, "req-mis", "TEST", "ref-mis",
                LocalDate.now(), JournalStatus.CONFIRMED,
                List.of(
                        new JournalLine("JLL-1", "JNL-MISMATCH", "leg-1", "CLIENT_ACC_001",
                                "AVAILABLE_BALANCE", "USD", EntryType.DEBIT, new BigDecimal("50.00"),
                                BigDecimal.ZERO, BigDecimal.ZERO, 1, Instant.now()),
                        new JournalLine("JLL-2", "JNL-MISMATCH", "leg-1", "COMPANY_FX_ACC",
                                "AVAILABLE_BALANCE", "USD", EntryType.CREDIT, new BigDecimal("30.00"),
                                BigDecimal.ZERO, BigDecimal.ZERO, 1, Instant.now())
                ),
                false, Instant.now());

        ReconciliationReport report = reconciliationService.runL1Reconciliation("2026-05-17",
                List.of(unbalanced));

        assertThat(report.l1Summary().balanceConsistencyPassed()).isFalse();
        assertThat(report.cases()).isNotEmpty();
    }

    @Test
    @DisplayName("TC-F007-04 runL2Reconciliation sub accounts sum match control no cases")
    void runL2Reconciliation_subAccountsSumMatchControl_noCases() {
        Map<String, BigDecimal> subAccounts = Map.of(
                "CLIENT_001", new BigDecimal("100.00"),
                "CLIENT_002", new BigDecimal("100.00"),
                "CLIENT_003", new BigDecimal("100.00"),
                "CLIENT_004", new BigDecimal("100.00"),
                "CLIENT_005", new BigDecimal("100.00"),
                "CLIENT_006", new BigDecimal("100.00"),
                "CLIENT_007", new BigDecimal("100.00"),
                "CLIENT_008", new BigDecimal("100.00"),
                "CLIENT_009", new BigDecimal("100.00"),
                "CLIENT_010", new BigDecimal("100.00")
        );

        ReconciliationReport report = reconciliationService.runL2Reconciliation(
                "2026-05-17", subAccounts, "CONTROL_CLIENT_USD",
                new BigDecimal("1000.00"), new BigDecimal("0.01"));

        assertThat(report.l2Summary().rulesPassed()).isEqualTo(1);
        assertThat(report.l2Summary().rulesFailed()).isEqualTo(0);
        assertThat(report.cases()).isEmpty();
    }

    @Test
    @DisplayName("TC-F007-05 runL2Reconciliation sub accounts sum mismatch case created")
    void runL2Reconciliation_subAccountsSumMismatch_caseCreated() {
        Map<String, BigDecimal> subAccounts = Map.of(
                "CLIENT_001", new BigDecimal("100.00"),
                "CLIENT_002", new BigDecimal("100.00"),
                "CLIENT_003", new BigDecimal("100.00"),
                "CLIENT_004", new BigDecimal("100.00"),
                "CLIENT_005", new BigDecimal("100.00"),
                "CLIENT_006", new BigDecimal("100.00"),
                "CLIENT_007", new BigDecimal("100.00"),
                "CLIENT_008", new BigDecimal("100.00"),
                "CLIENT_009", new BigDecimal("100.00"),
                "CLIENT_010", new BigDecimal("100.00")
        );
        BigDecimal expected = new BigDecimal("999.00");
        BigDecimal tolerance = new BigDecimal("0.01");

        ReconciliationReport report = reconciliationService.runL2Reconciliation(
                "2026-05-17", subAccounts, "CONTROL_CLIENT_USD", expected, tolerance);

        assertThat(report.l2Summary().rulesFailed()).isEqualTo(1);
        assertThat(report.cases()).isNotEmpty();
        assertThat(report.cases().get(0).discrepancy()).isEqualByComparingTo(new BigDecimal("1.00"));
    }

    @Test
    @DisplayName("TC-F007-06 runL3Reconciliation external file matched all matched")
    void runL3Reconciliation_externalFileMatched_allMatched() {
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("100000.00"));
        setBalance("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD", new BigDecimal("100000.00"));

        List<Journal> journals = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            CommandResult r = stateMachine.applyPosting(new PostingCommand(
                    "req-ext-" + i, "EXT", "EXT-REF-" + i, LocalDate.now(),
                    List.of(new PostingCommand.Leg("leg-1", "TEST", List.of(
                            new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                    EntryType.DEBIT, new BigDecimal("10.00"), "D"),
                            new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD",
                                    EntryType.CREDIT, new BigDecimal("10.00"), "C")
                    )))
            ));
            journals.add(stateMachine.getJournal(r.journalId()));
        }

        List<ReconciliationService.ExternalRecord> external = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            external.add(new ReconciliationService.ExternalRecord(
                    "EXT-REF-" + i, new BigDecimal("10.00")));
        }

        ReconciliationReport report = reconciliationService.runL3Reconciliation(
                "2026-05-17", external, journals);

        assertThat(report.l3Summary().matched()).isEqualTo(100);
        assertThat(report.l3Summary().internalOnly()).isEqualTo(0);
        assertThat(report.l3Summary().externalOnly()).isEqualTo(0);
    }

    @Test
    @DisplayName("TC-F007-07 runL3Reconciliation internal only case created")
    void runL3Reconciliation_internalOnly_caseCreated() {
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("1000.00"));
        setBalance("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD", new BigDecimal("1000.00"));

        CommandResult r = stateMachine.applyPosting(new PostingCommand(
                "req-int-only", "INT", "INT-ONLY-REF", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.DEBIT, new BigDecimal("10.00"), "D"),
                        new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD",
                                EntryType.CREDIT, new BigDecimal("10.00"), "C")
                )))
        ));

        ReconciliationReport report = reconciliationService.runL3Reconciliation(
                "2026-05-17", List.of(), List.of(stateMachine.getJournal(r.journalId())));

        assertThat(report.l3Summary().internalOnly()).isEqualTo(1);
        assertThat(report.cases()).isNotEmpty();
    }

    @Test
    @DisplayName("TC-F007-08 runL3Reconciliation amount mismatch case created")
    void runL3Reconciliation_amountMismatch_caseCreated() {
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("1000.00"));
        setBalance("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD", new BigDecimal("1000.00"));

        CommandResult r = stateMachine.applyPosting(new PostingCommand(
                "req-mis", "EXT", "EXT-MISMATCH", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.DEBIT, new BigDecimal("800.00"), "D"),
                        new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD",
                                EntryType.CREDIT, new BigDecimal("800.00"), "C")
                )))
        ));

        List<ReconciliationService.ExternalRecord> external = List.of(
                new ReconciliationService.ExternalRecord("EXT-MISMATCH", new BigDecimal("810.00")));

        ReconciliationReport report = reconciliationService.runL3Reconciliation(
                "2026-05-17", external, List.of(stateMachine.getJournal(r.journalId())));

        assertThat(report.l3Summary().amountMismatch()).isEqualTo(1);
        assertThat(report.cases()).isNotEmpty();
        assertThat(report.cases().get(0).discrepancy()).isEqualByComparingTo(new BigDecimal("10.00"));
    }
}

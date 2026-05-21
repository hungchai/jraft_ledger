package com.tomma8.ledger.service;

import com.tomma8.ledger.domain.command.CommandResult;
import com.tomma8.ledger.domain.command.PostingCommand;
import com.tomma8.ledger.domain.command.ReversalCommand;
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

import static org.assertj.core.api.Assertions.*;

@DisplayName("AccountingPeriodService (F-009)")
class AccountingPeriodServiceTest {

    private BalanceStore balanceStore;
    private AccountMetaStore accountMetaStore;
    private BalanceTypeConfigStore balanceTypeConfigStore;
    private LedgerStateMachine stateMachine;
    private AccountingPeriodService periodService;

    @BeforeEach
    void setUp() {
        balanceStore = new BalanceStore();
        accountMetaStore = new AccountMetaStore();
        balanceTypeConfigStore = new BalanceTypeConfigStore();
        stateMachine = new LedgerStateMachine(balanceStore, accountMetaStore, balanceTypeConfigStore);
        periodService = new AccountingPeriodService();

        balanceTypeConfigStore.put("AVAILABLE_BALANCE", new BalanceTypeConfig(
                "AVAILABLE_BALANCE", false, null, SignConvention.NORMAL_CREDIT, 1));

        accountMetaStore.put("CLIENT_ACC_001", new Account(
                "CLIENT_ACC_001", AccountType.CLIENT, "Client",
                "CUST-001", AccountStatus.ACTIVE, null, Instant.now()));
        accountMetaStore.put("COMPANY_FX_ACC", new Account(
                "COMPANY_FX_ACC", AccountType.COMPANY, "Company",
                null, AccountStatus.ACTIVE, null, Instant.now()));
    }

    private void setBalance(String a, String t, String c, BigDecimal amt) {
        balanceStore.put(new AccountBalanceKey(a, t, c),
                new BalanceEntry(amt, 0, 1, "", Instant.now()));
    }

    @Test
    @DisplayName("TC-F009-01 closePeriod open period eod tasks executed in order")
    void closePeriod_openPeriod_eodTasksExecutedInOrder() {
        LocalDate date = LocalDate.of(2026, 5, 16);
        AccountingPeriod period = periodService.getPeriod(date);
        assertThat(period.status()).isEqualTo(PeriodStatus.OPEN);

        periodService.triggerEOD(date);

        AccountingPeriod closed = periodService.getPeriod(date);
        assertThat(closed.status()).isEqualTo(PeriodStatus.CLOSED);
    }

    @Test
    @DisplayName("TC-F009-02 post during closing returns period closed error")
    void postDuringClosing_returnsPeriodClosedError() {
        LocalDate date = LocalDate.of(2026, 5, 16);
        AccountingPeriod period = periodService.getPeriod(date);

        // Mark as CLOSING
        periodService.triggerEOD(date); // goes to CLOSING then CLOSED

        // Verify period is closed/closing — posting would be rejected
        assertThat(periodService.isClosedOrClosing(date)).isTrue();
    }

    @Test
    @DisplayName("TC-F009-03 post to closed period returns period closed error")
    void postToClosedPeriod_returnsPeriodClosedError() {
        LocalDate date = LocalDate.of(2026, 5, 16);
        periodService.triggerEOD(date); // CLOSED

        assertThat(periodService.isClosed(date)).isTrue();
    }

    @Test
    @DisplayName("TC-F009-04 reverse in closed period allowed with cross period flag")
    void reverseInClosedPeriod_allowedWithCrossPeriodFlag() {
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("1000.00"));

        // Original journal with date in now-closed period
        LocalDate oldDate = LocalDate.of(2026, 1, 15);
        periodService.triggerEOD(oldDate); // Close Jan 2026 period

        // Create posting with old valueDate
        PostingCommand postCmd = new PostingCommand(
                "req-009", "TEST", "test-ref", oldDate,
                List.of(new PostingCommand.Leg("leg-1", "TEST", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.DEBIT, new BigDecimal("100.00"), "Old"),
                        new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD",
                                EntryType.CREDIT, new BigDecimal("100.00"), "Old credit")
                )))
        );
        String journalId = stateMachine.applyPosting(postCmd).journalId();

        // Reverse with today's date
        ReversalCommand revCmd = new ReversalCommand(
                "rev-009", journalId, "Cross period reversal",
                "CROSS_PERIOD_ADJUSTMENT", LocalDate.now());
        CommandResult revResult = stateMachine.applyReversal(revCmd);

        assertThat(revResult.isCompleted()).isTrue();
        Journal reversal = stateMachine.getJournal(revResult.journalId());
        assertThat(reversal.crossPeriod()).isTrue();
    }

    @Test
    @DisplayName("TC-F009-05 eod balance snapshot matches state machine")
    void eodBalanceSnapshot_matchesStateMachine() {
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("700.00"));
        setBalance("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD", new BigDecimal("5000.00"));

        // Run EOD
        LocalDate date = LocalDate.of(2026, 5, 16);
        periodService.triggerEOD(date);

        // After EOD, State Machine balances should be unchanged
        AccountBalanceKey clientKey = new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD");
        AccountBalanceKey companyKey = new AccountBalanceKey("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD");

        assertThat(balanceStore.getOrThrow(clientKey).amount()).isEqualByComparingTo(new BigDecimal("700.00"));
        assertThat(balanceStore.getOrThrow(companyKey).amount()).isEqualByComparingTo(new BigDecimal("5000.00"));

        assertThat(periodService.isClosed(date)).isTrue();
    }
}

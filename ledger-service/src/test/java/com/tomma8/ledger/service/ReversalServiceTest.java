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

@DisplayName("ReversalService (F-004)")
class ReversalServiceTest {

    private BalanceStore balanceStore;
    private AccountMetaStore accountMetaStore;
    private BalanceTypeConfigStore balanceTypeConfigStore;
    private LedgerStateMachine stateMachine;
    private ReversalService reversalService;

    @BeforeEach
    void setUp() {
        balanceStore = new BalanceStore();
        accountMetaStore = new AccountMetaStore();
        balanceTypeConfigStore = new BalanceTypeConfigStore();
        stateMachine = new LedgerStateMachine(balanceStore, accountMetaStore, balanceTypeConfigStore);
        reversalService = new ReversalService(stateMachine);

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

    private String createOriginalJournal() {
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("1000.00"));
        setBalance("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD", new BigDecimal("5000.00"));
        PostingCommand cmd = new PostingCommand(
                "req-001", "TEST", "test-ref", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.DEBIT, new BigDecimal("800.00"), "Client"),
                        new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD",
                                EntryType.CREDIT, new BigDecimal("800.00"), "Company")
                )))
        );
        return stateMachine.applyPosting(cmd).journalId();
    }

    @Test
    @DisplayName("TC-F004-01 reverse confirmed journal creates reversal journal")
    void reverse_confirmedJournal_reversalJournalCreated() {
        String originalId = createOriginalJournal();

        ReversalCommand revCmd = new ReversalCommand(
                "rev-001", originalId, "Test reversal", "CANCELLATION", LocalDate.now());
        CommandResult result = reversalService.reverse(revCmd);

        assertThat(result.isCompleted()).isTrue();
        assertThat(result.journalId()).isNotNull();

        // Original journal now reversed
        assertThat(stateMachine.getJournal(originalId).status()).isEqualTo(JournalStatus.REVERSED);
    }

    @Test
    @DisplayName("TC-F004-02 reverse already reversed journal returns rejected")
    void reverse_alreadyReversedJournal_returnsRejected() {
        String originalId = createOriginalJournal();
        reversalService.reverse(new ReversalCommand(
                "rev-001", originalId, "First", "CANCEL", LocalDate.now()));

        CommandResult second = reversalService.reverse(new ReversalCommand(
                "rev-002", originalId, "Second", "CANCEL", LocalDate.now()));

        assertThat(second.isRejected()).isTrue();
        assertThat(second.errorCodes()).contains("JOURNAL_ALREADY_REVERSED");
    }

    @Test
    @DisplayName("TC-F004-03 reverse reversal journal returns rejected")
    void reverse_reversalJournal_returnsRejected() {
        String originalId = createOriginalJournal();
        String reversalId = reversalService.reverse(new ReversalCommand(
                "rev-001", originalId, "First", "CANCEL", LocalDate.now())).journalId();

        CommandResult revOfRev = reversalService.reverse(new ReversalCommand(
                "rev-002", reversalId, "Reverse reversal", "CANCEL", LocalDate.now()));

        assertThat(revOfRev.isRejected()).isTrue();
        assertThat(revOfRev.errorCodes()).contains("CANNOT_REVERSE_REVERSAL");
    }

    @Test
    @DisplayName("TC-F004-04 reverse same requestId twice idempotent")
    void reverse_sameRequestIdTwice_idempotent() {
        String originalId = createOriginalJournal();

        ReversalCommand cmd = new ReversalCommand(
                "rev-004", originalId, "Test", "CANCEL", LocalDate.now());
        CommandResult first = reversalService.reverse(cmd);
        CommandResult second = reversalService.reverse(cmd);

        assertThat(first.isCompleted()).isTrue();
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("TC-F004-06 reverse mirrors original lines debit credit swapped")
    void reverse_mirrorsOriginalLines_debitCreditSwapped() {
        String originalId = createOriginalJournal();

        ReversalCommand revCmd = new ReversalCommand(
                "rev-006", originalId, "Mirror test", "CANCELLATION", LocalDate.now());
        CommandResult result = reversalService.reverse(revCmd);

        assertThat(result.isCompleted()).isTrue();
        Journal reversal = stateMachine.getJournal(result.journalId());
        assertThat(reversal.lines()).hasSize(2);

        Journal original = stateMachine.getJournal(originalId);
        for (int i = 0; i < 2; i++) {
            JournalLine origLine = original.lines().get(i);
            JournalLine revLine = reversal.lines().get(i);
            assertThat(revLine.entryType()).isNotEqualTo(origLine.entryType());
            assertThat(revLine.amount()).isEqualByComparingTo(origLine.amount());
        }
    }

    @Test
    @DisplayName("TC-F004-07 reverse insufficient balance still executes")
    void reverse_insufficientBalance_stillExecutes() {
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("1000.00"));
        setBalance("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD", new BigDecimal("10000.00"));

        // Post a CREDIT to CLIENT
        PostingCommand postCmd = new PostingCommand(
                "req-007", "TEST", "test-ref", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST", List.of(
                        new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD",
                                EntryType.DEBIT, new BigDecimal("1000.00"), "Company"),
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.CREDIT, new BigDecimal("1000.00"), "Client")
                )))
        );
        String journalId = stateMachine.applyPosting(postCmd).journalId();

        // Client spends all 2000 (original 1000 + the 1000 credit), balance goes to 0
        PostingCommand spendCmd = new PostingCommand(
                "req-007b", "TEST", "spend", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-2", "TEST", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.DEBIT, new BigDecimal("2000.00"), "Spend all"),
                        new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD",
                                EntryType.CREDIT, new BigDecimal("2000.00"), "Receive")
                )))
        );
        stateMachine.applyPosting(spendCmd);

        // Reverse the original: CLIENT DEBIT 1000 → goes negative, no balance check
        CommandResult revResult = reversalService.reverse(new ReversalCommand(
                "rev-007", journalId, "Force reversal", "CANCEL", LocalDate.now()));

        assertThat(revResult.isCompleted()).isTrue();
        AccountBalanceKey clientKey = new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD");
        assertThat(balanceStore.getOrThrow(clientKey).amount()).isEqualByComparingTo(new BigDecimal("-1000.00"));
    }
}

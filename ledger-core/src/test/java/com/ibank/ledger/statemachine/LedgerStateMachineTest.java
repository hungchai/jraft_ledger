package com.ibank.ledger.statemachine;

import com.ibank.ledger.domain.command.CommandResult;
import com.ibank.ledger.domain.command.PostingCommand;
import com.ibank.ledger.domain.command.ReversalCommand;
import com.ibank.ledger.domain.model.*;
import com.ibank.ledger.store.AccountMetaStore;
import com.ibank.ledger.store.BalanceStore;
import com.ibank.ledger.store.BalanceTypeConfigStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("LedgerStateMachine — Balance Operations (F-008)")
class LedgerStateMachineTest {

    private BalanceStore balanceStore;
    private AccountMetaStore accountMetaStore;
    private BalanceTypeConfigStore balanceTypeConfigStore;
    private LedgerStateMachine stateMachine;

    @BeforeEach
    void setUp() {
        balanceStore = new BalanceStore();
        accountMetaStore = new AccountMetaStore();
        balanceTypeConfigStore = new BalanceTypeConfigStore();
        stateMachine = new LedgerStateMachine(balanceStore, accountMetaStore, balanceTypeConfigStore);

        // Register balance types
        balanceTypeConfigStore.put("AVAILABLE_BALANCE", new BalanceTypeConfig(
                "AVAILABLE_BALANCE", false, null,
                SignConvention.NORMAL_CREDIT, 1));
        balanceTypeConfigStore.put("TRADE_AHEAD_BALANCE", new BalanceTypeConfig(
                "TRADE_AHEAD_BALANCE", true, NegativeSemantics.PRE_AUTHORIZED,
                SignConvention.NORMAL_DEBIT, 1));

        // Create test accounts
        accountMetaStore.put("CLIENT_ACC_001", new Account(
                "CLIENT_ACC_001", AccountType.CLIENT, "Client 001",
                "CUST-001", AccountStatus.ACTIVE, null, Instant.now()));
        accountMetaStore.put("COMPANY_FX_ACC", new Account(
                "COMPANY_FX_ACC", AccountType.COMPANY, "Company FX",
                null, AccountStatus.ACTIVE, null, Instant.now()));
    }

    private void setBalance(String accountId, String balanceType, String currency, BigDecimal amount) {
        AccountBalanceKey key = new AccountBalanceKey(accountId, balanceType, currency);
        balanceStore.put(key, new BalanceEntry(amount, 0, 1, "", Instant.now()));
    }

    @Test
    @DisplayName("TC-F008-01 applyPosting single debit balance decreased")
    void applyPosting_singleDebit_balanceDecreased() {
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("1000.00"));

        PostingCommand cmd = new PostingCommand(
                "req-001", "TEST", "test-ref", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST_TYPE", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.DEBIT, new BigDecimal("300.00"), "Test debit")
                )))
        );

        CommandResult result = stateMachine.applyPosting(cmd);

        assertThat(result.isCompleted()).isTrue();
        AccountBalanceKey key = new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD");
        assertThat(balanceStore.getOrThrow(key).amount()).isEqualByComparingTo(new BigDecimal("700.00"));
    }

    @Test
    @DisplayName("TC-F008-02 applyPosting single credit balance increased")
    void applyPosting_singleCredit_balanceIncreased() {
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("1000.00"));

        PostingCommand cmd = new PostingCommand(
                "req-002", "TEST", "test-ref", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST_TYPE", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.CREDIT, new BigDecimal("500.00"), "Test credit")
                )))
        );

        CommandResult result = stateMachine.applyPosting(cmd);

        assertThat(result.isCompleted()).isTrue();
        AccountBalanceKey key = new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD");
        assertThat(balanceStore.getOrThrow(key).amount()).isEqualByComparingTo(new BigDecimal("1500.00"));
    }

    @Test
    @DisplayName("TC-F008-03 applyPosting debit exceeds balance allowNegative=false command rejected")
    void applyPosting_debitExceedsBalance_allowNegativeFalse_commandRejected() {
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("100.00"));

        PostingCommand cmd = new PostingCommand(
                "req-003", "TEST", "test-ref", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST_TYPE", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.DEBIT, new BigDecimal("200.00"), "Test")
                )))
        );

        CommandResult result = stateMachine.applyPosting(cmd);

        assertThat(result.status()).isEqualTo(CommandResult.REJECTED);
        assertThat(result.errorCodes()).contains("INSUFFICIENT_BALANCE");

        AccountBalanceKey key = new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD");
        assertThat(balanceStore.getOrThrow(key).amount()).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("TC-F008-04 applyPosting tradeAheadBalance allowNegative=true negative balance allowed")
    void applyPosting_tradeAheadBalance_allowNegativeTrue_negativeBalanceAllowed() {
        setBalance("CLIENT_ACC_001", "TRADE_AHEAD_BALANCE", "USD", BigDecimal.ZERO);

        PostingCommand cmd = new PostingCommand(
                "req-004", "TEST", "test-ref", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST_TYPE", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "TRADE_AHEAD_BALANCE", "USD",
                                EntryType.DEBIT, new BigDecimal("50000.00"), "Trade")
                )))
        );

        CommandResult result = stateMachine.applyPosting(cmd);

        assertThat(result.isCompleted()).isTrue();
        AccountBalanceKey key = new AccountBalanceKey("CLIENT_ACC_001", "TRADE_AHEAD_BALANCE", "USD");
        assertThat(balanceStore.getOrThrow(key).amount()).isEqualByComparingTo(new BigDecimal("-50000.00"));
    }

    @Test
    @DisplayName("TC-F008-05 applyPosting tradeAheadBalance credit above zero command rejected")
    void applyPosting_tradeAheadBalance_creditAboveZero_commandRejected() {
        setBalance("CLIENT_ACC_001", "TRADE_AHEAD_BALANCE", "USD", new BigDecimal("-10000.00"));

        PostingCommand cmd = new PostingCommand(
                "req-005", "TEST", "test-ref", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST_TYPE", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "TRADE_AHEAD_BALANCE", "USD",
                                EntryType.CREDIT, new BigDecimal("20000.00"), "Credit above zero")
                )))
        );

        CommandResult result = stateMachine.applyPosting(cmd);

        assertThat(result.status()).isEqualTo(CommandResult.REJECTED);
        assertThat(result.errorCodes()).contains("CREDIT_EXCEEDS_LIMIT");
    }

    @Test
    @DisplayName("TC-F008-06 applyPosting multi-account atomic update")
    void applyPosting_multiAccount_atomicUpdate() {
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("1000.00"));
        setBalance("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD", new BigDecimal("5000.00"));

        PostingCommand cmd = new PostingCommand(
                "req-006", "RFQ_SETTLEMENT", "RFQ-001", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TRADE_SETTLEMENT", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.DEBIT, new BigDecimal("800.00"), "Client pay"),
                        new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD",
                                EntryType.CREDIT, new BigDecimal("800.00"), "Company receive")
                )))
        );

        CommandResult result = stateMachine.applyPosting(cmd);

        assertThat(result.isCompleted()).isTrue();

        AccountBalanceKey clientKey = new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD");
        AccountBalanceKey companyKey = new AccountBalanceKey("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD");

        assertThat(balanceStore.getOrThrow(clientKey).amount()).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(balanceStore.getOrThrow(companyKey).amount()).isEqualByComparingTo(new BigDecimal("5800.00"));
    }

    @Test
    @DisplayName("TC-F008-07 applyPosting frozen account command rejected")
    void applyPosting_frozenAccount_commandRejected() {
        accountMetaStore.put("CLIENT_ACC_001",
                accountMetaStore.getOrThrow("CLIENT_ACC_001").withStatus(AccountStatus.FROZEN));
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("1000.00"));

        PostingCommand cmd = new PostingCommand(
                "req-007", "TEST", "test-ref", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST_TYPE", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.DEBIT, new BigDecimal("100.00"), "Test")
                )))
        );

        CommandResult result = stateMachine.applyPosting(cmd);

        assertThat(result.status()).isEqualTo(CommandResult.REJECTED);
        assertThat(result.errorCodes()).contains("ACCOUNT_FROZEN");
    }

    @Test
    @DisplayName("TC-F008-08 applyPosting idempotency same requestId returns same result")
    void applyPosting_idempotency_sameRequestId_returnsSameResult() {
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("1000.00"));

        PostingCommand cmd = new PostingCommand(
                "req-001", "TEST", "test-ref", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST_TYPE", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.DEBIT, new BigDecimal("300.00"), "Test")
                )))
        );

        CommandResult first = stateMachine.applyPosting(cmd);
        CommandResult second = stateMachine.applyPosting(cmd);

        assertThat(first.isCompleted()).isTrue();
        assertThat(second).isEqualTo(first);

        AccountBalanceKey key = new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD");
        assertThat(balanceStore.getOrThrow(key).amount()).isEqualByComparingTo(new BigDecimal("700.00"));
    }

    @Test
    @DisplayName("TC-F008-09 applyPosting journal unbalanced command rejected")
    void applyPosting_journalUnbalanced_commandRejected() {
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("1000.00"));
        setBalance("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD", new BigDecimal("5000.00"));

        PostingCommand cmd = new PostingCommand(
                "req-009", "TEST", "test-ref", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST_TYPE", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.DEBIT, new BigDecimal("100.00"), "Client"),
                        new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD",
                                EntryType.CREDIT, new BigDecimal("99.00"), "Company")
                )))
        );

        CommandResult result = stateMachine.applyPosting(cmd);

        assertThat(result.status()).isEqualTo(CommandResult.REJECTED);
        assertThat(result.errorCodes()).contains("JOURNAL_UNBALANCED");
    }

    @Test
    @DisplayName("TC-F008-10 applyPosting generateJournalLine balanceBefore and balanceAfter correct")
    void applyPosting_generateJournalLine_balanceBeforeAndAfterCorrect() {
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("1000.00"));

        PostingCommand cmd = new PostingCommand(
                "req-010", "TEST", "test-ref", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST_TYPE", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.DEBIT, new BigDecimal("300.00"), "Test")
                )))
        );

        CommandResult result = stateMachine.applyPosting(cmd);

        assertThat(result.isCompleted()).isTrue();
        assertThat(result.journalId()).isNotNull();

        // Verify journal was generated via stateMachine
        Journal generatedJournal = stateMachine.getJournal(result.journalId());
        assertThat(generatedJournal).isNotNull();
        assertThat(generatedJournal.lines()).hasSize(1);

        JournalLine line = generatedJournal.lines().get(0);
        assertThat(line.balanceBefore()).isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(line.balanceAfter()).isEqualByComparingTo(new BigDecimal("700.00"));
    }

    // ── accountSeq Tests (v0.2) ───────────────────────────────────

    @Test
    @DisplayName("TC-F008-19 applyPosting first ever accountSeq starts at 1")
    void applyPosting_firstEver_accountSeqStartsAtOne() {
        // Fresh balance with accountSeq=0 (never transacted), but sufficient balance
        AccountBalanceKey key = new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD");
        balanceStore.put(key, new BalanceEntry(new BigDecimal("1000.00"), 0, 0, "", Instant.EPOCH));

        PostingCommand cmd = new PostingCommand(
                "req-019", "TEST", "test-ref", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST_TYPE", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.DEBIT, new BigDecimal("100.00"), "First ever")
                )))
        );

        stateMachine.applyPosting(cmd);

        assertThat(balanceStore.getOrThrow(key).accountSeq()).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-F008-20 applyPosting subsequent posting accountSeq incremented")
    void applyPosting_subsequentPosting_accountSeqIncremented() {
        // Pre-set accountSeq to 5
        AccountBalanceKey key = new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD");
        balanceStore.put(key, new BalanceEntry(new BigDecimal("1000.00"), 5, 5, "JNL-005", Instant.now()));

        PostingCommand cmd = new PostingCommand(
                "req-020", "TEST", "test-ref", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST_TYPE", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.DEBIT, new BigDecimal("100.00"), "Subsequent")
                )))
        );

        stateMachine.applyPosting(cmd);

        assertThat(balanceStore.getOrThrow(key).accountSeq()).isEqualTo(6);
    }

    @Test
    @DisplayName("TC-F008-23 applyPosting different balance type seq independent")
    void applyPosting_differentBalanceType_seqIndependent() {
        AccountBalanceKey availKey = new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD");
        AccountBalanceKey tradeKey = new AccountBalanceKey("CLIENT_ACC_001", "TRADE_AHEAD_BALANCE", "USD");

        balanceStore.put(availKey, new BalanceEntry(new BigDecimal("1000.00"), 5, 5, "JNL-005", Instant.now()));
        balanceStore.put(tradeKey, new BalanceEntry(BigDecimal.ZERO, 3, 3, "JNL-003", Instant.now()));

        PostingCommand cmd = new PostingCommand(
                "req-023", "TEST", "test-ref", LocalDate.now(),
                List.of(
                        new PostingCommand.Leg("leg-1", "TEST_TYPE", List.of(
                                new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                        EntryType.DEBIT, new BigDecimal("200.00"), "Avail")
                        )),
                        new PostingCommand.Leg("leg-2", "TEST_TYPE", List.of(
                                new PostingCommand.Line("CLIENT_ACC_001", "TRADE_AHEAD_BALANCE", "USD",
                                        EntryType.DEBIT, new BigDecimal("200.00"), "Trade")
                        ))
                )
        );

        stateMachine.applyPosting(cmd);

        assertThat(balanceStore.getOrThrow(availKey).accountSeq()).isEqualTo(6);
        assertThat(balanceStore.getOrThrow(tradeKey).accountSeq()).isEqualTo(4);
    }

    // ── Reversal Tests (F-008 Section 3.2) ────────────────────────

    @Test
    @DisplayName("TC-F008-11 applyReversal confirmed journal balance reverted")
    void applyReversal_confirmedJournal_balanceReverted() {
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("1000.00"));

        // First, create a posting
        PostingCommand postCmd = new PostingCommand(
                "req-011", "TEST", "test-ref", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST_TYPE", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.DEBIT, new BigDecimal("300.00"), "Original debit")
                )))
        );
        CommandResult postResult = stateMachine.applyPosting(postCmd);
        String originalJournalId = postResult.journalId();

        AccountBalanceKey key = new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD");
        assertThat(balanceStore.getOrThrow(key).amount()).isEqualByComparingTo(new BigDecimal("700.00"));

        // Reversal
        ReversalCommand revCmd = new ReversalCommand(
                "rev-011", originalJournalId,
                "Test reversal", "CANCELLATION", LocalDate.now());
        CommandResult revResult = stateMachine.applyReversal(revCmd);

        assertThat(revResult.isCompleted()).isTrue();
        // Balance restored
        assertThat(balanceStore.getOrThrow(key).amount()).isEqualByComparingTo(new BigDecimal("1000.00"));
        // Original journal marked as reversed
        assertThat(stateMachine.getJournal(originalJournalId).status()).isEqualTo(JournalStatus.REVERSED);
    }

    @Test
    @DisplayName("TC-F008-12 applyReversal already reversed journal command rejected")
    void applyReversal_alreadyReversedJournal_commandRejected() {
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("1000.00"));

        PostingCommand postCmd = new PostingCommand(
                "req-012", "TEST", "test-ref", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST_TYPE", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.DEBIT, new BigDecimal("100.00"), "Debit")
                )))
        );
        CommandResult postResult = stateMachine.applyPosting(postCmd);
        String journalId = postResult.journalId();

        // First reversal succeeds
        stateMachine.applyReversal(new ReversalCommand("rev-012a", journalId, "First", "CANCEL", LocalDate.now()));

        // Second reversal must fail
        CommandResult secondRev = stateMachine.applyReversal(new ReversalCommand(
                "rev-012b", journalId, "Second", "CANCEL", LocalDate.now()));

        assertThat(secondRev.isRejected()).isTrue();
        assertThat(secondRev.errorCodes()).contains("JOURNAL_ALREADY_REVERSED");
    }

    @Test
    @DisplayName("TC-F008-13 applyReversal reversal journal command rejected")
    void applyReversal_reversalJournal_commandRejected() {
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("1000.00"));

        PostingCommand postCmd = new PostingCommand(
                "req-013", "TEST", "test-ref", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST_TYPE", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.DEBIT, new BigDecimal("100.00"), "Debit")
                )))
        );
        String originalId = stateMachine.applyPosting(postCmd).journalId();

        // Reversal creates a REVERSAL-type journal
        CommandResult revResult = stateMachine.applyReversal(new ReversalCommand(
                "rev-013a", originalId, "Test", "CANCEL", LocalDate.now()));
        String reversalJournalId = revResult.journalId();

        // Reversing the reversal journal should fail
        CommandResult revOfRev = stateMachine.applyReversal(new ReversalCommand(
                "rev-013b", reversalJournalId, "Reverse reversal", "CANCEL", LocalDate.now()));

        assertThat(revOfRev.isRejected()).isTrue();
        assertThat(revOfRev.errorCodes()).contains("CANNOT_REVERSE_REVERSAL");
    }

    @Test
    @DisplayName("TC-F008-14 applyReversal no balance check executes even if insufficient balance")
    void applyReversal_noBalanceCheck_executesEvenIfInsufficientBalance() {
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("1000.00"));
        setBalance("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD", new BigDecimal("10000.00"));

        PostingCommand postCmd = new PostingCommand(
                "req-014", "TEST", "test-ref", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST_TYPE", List.of(
                        new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD",
                                EntryType.DEBIT, new BigDecimal("1000.00"), "Company debit"),
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.CREDIT, new BigDecimal("1000.00"), "Client credit")
                )))
        );
        CommandResult postResult = stateMachine.applyPosting(postCmd);
        String journalId = postResult.journalId();

        // Client's balance is now 2000 (1000 + 1000)
        AccountBalanceKey clientKey = new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD");
        assertThat(balanceStore.getOrThrow(clientKey).amount()).isEqualByComparingTo(new BigDecimal("2000.00"));

        // Client spends the money with another posting (DEBIT 2000 → 0)
        PostingCommand spendCmd = new PostingCommand(
                "req-014b", "TEST", "spend", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-2", "TEST_TYPE", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.DEBIT, new BigDecimal("2000.00"), "Spend"),
                        new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD",
                                EntryType.CREDIT, new BigDecimal("2000.00"), "Receive")
                )))
        );
        stateMachine.applyPosting(spendCmd);
        assertThat(balanceStore.getOrThrow(clientKey).amount()).isEqualByComparingTo(BigDecimal.ZERO);

        // Reversal of the original CREDIT 1000 → DEBIT 1000 from client, goes negative
        // No balance check for reversal — should succeed
        ReversalCommand revCmd = new ReversalCommand(
                "rev-014", journalId, "Reversal after spend", "CANCELLATION", LocalDate.now());
        CommandResult revResult = stateMachine.applyReversal(revCmd);

        assertThat(revResult.isCompleted()).isTrue();
        // Balance goes negative (no check)
        assertThat(balanceStore.getOrThrow(clientKey).amount()).isEqualByComparingTo(new BigDecimal("-1000.00"));
    }

    @Test
    @DisplayName("TC-F008-15 applyReversal cross period marked correctly")
    void applyReversal_crossPeriod_markedCorrectly() {
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("1000.00"));

        // Original journal with an old valueDate
        PostingCommand postCmd = new PostingCommand(
                "req-015", "TEST", "test-ref", LocalDate.of(2026, 1, 15),
                List.of(new PostingCommand.Leg("leg-1", "TEST_TYPE", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.DEBIT, new BigDecimal("100.00"), "Old debit")
                )))
        );
        CommandResult postResult = stateMachine.applyPosting(postCmd);
        String journalId = postResult.journalId();

        ReversalCommand revCmd = new ReversalCommand(
                "rev-015", journalId, "Cross period reversal",
                "CROSS_PERIOD_ADJUSTMENT", LocalDate.now());
        CommandResult revResult = stateMachine.applyReversal(revCmd);

        assertThat(revResult.isCompleted()).isTrue();
        Journal reversalJournal = stateMachine.getJournal(revResult.journalId());
        assertThat(reversalJournal).isNotNull();
        assertThat(reversalJournal.crossPeriod()).isTrue();
    }

    @Test
    @DisplayName("TC-F008-22 applyAdjustment accountSeq incremented")
    void applyAdjustment_accountSeqIncremented() {
        // Start with accountSeq=20
        AccountBalanceKey key = new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD");
        balanceStore.put(key, new BalanceEntry(new BigDecimal("1000.00"), 20, 20, "JNL-020", Instant.now()));
        setBalance("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD", new BigDecimal("5000.00"));

        PostingCommand adjCmd = new PostingCommand(
                "adj-022", "MANUAL_ADJUSTMENT", "ADJ-CASE-022", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "ADJUSTMENT", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.DEBIT, new BigDecimal("100.00"), "Adjustment debit"),
                        new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD",
                                EntryType.CREDIT, new BigDecimal("100.00"), "Adjustment credit")
                )))
        );

        stateMachine.applyPosting(adjCmd);

        assertThat(balanceStore.getOrThrow(key).accountSeq()).isEqualTo(21);
    }

    @Test
    @DisplayName("TC-F008-21 applyReversal accountSeq incremented")
    void applyReversal_accountSeqIncremented() {
        // Start with accountSeq=10
        AccountBalanceKey key = new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD");
        balanceStore.put(key, new BalanceEntry(new BigDecimal("1000.00"), 10, 10, "JNL-010", Instant.now()));

        PostingCommand postCmd = new PostingCommand(
                "req-021", "TEST", "test-ref", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST_TYPE", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.DEBIT, new BigDecimal("100.00"), "Original")
                )))
        );
        String journalId = stateMachine.applyPosting(postCmd).journalId();

        // Posting incremented accountSeq: 10 → 11
        assertThat(balanceStore.getOrThrow(key).accountSeq()).isEqualTo(11);

        // Reversal increments accountSeq again: 11 → 12
        stateMachine.applyReversal(new ReversalCommand(
                "rev-021", journalId, "Test", "CANCEL", LocalDate.now()));

        assertThat(balanceStore.getOrThrow(key).accountSeq()).isEqualTo(12);
    }
}

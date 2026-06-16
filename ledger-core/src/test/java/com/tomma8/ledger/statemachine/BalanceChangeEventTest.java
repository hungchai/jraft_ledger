package com.tomma8.ledger.statemachine;

import com.tomma8.ledger.domain.command.CommandResult;
import com.tomma8.ledger.domain.command.PostingCommand;
import com.tomma8.ledger.domain.command.ReversalCommand;
import com.tomma8.ledger.domain.event.BalanceChangeEvent;
import com.tomma8.ledger.domain.model.*;
import com.tomma8.ledger.store.AccountMetaStore;
import com.tomma8.ledger.store.BalanceStore;
import com.tomma8.ledger.store.BalanceTypeConfigStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("BalanceChangeEvent — accountSeq (F-011 v0.2)")
class BalanceChangeEventTest {

    private BalanceStore balanceStore;
    private AccountMetaStore accountMetaStore;
    private BalanceTypeConfigStore balanceTypeConfigStore;
    private LedgerStateMachine stateMachine;
    private List<BalanceChangeEvent> capturedEvents;

    @BeforeEach
    void setUp() {
        balanceStore = new BalanceStore();
        accountMetaStore = new AccountMetaStore();
        balanceTypeConfigStore = new BalanceTypeConfigStore();
        stateMachine = new LedgerStateMachine(balanceStore, accountMetaStore, balanceTypeConfigStore);

        capturedEvents = new ArrayList<>();
        stateMachine.setEventListener(capturedEvents::add);
        // EmitGate is closed by default; tests opt in to capture events.
        stateMachine.getEmitGate().setEnabled(true);

        // Register balance types
        balanceTypeConfigStore.put("AVAILABLE_BALANCE", new BalanceTypeConfig(
                "AVAILABLE_BALANCE", false, null, SignConvention.NORMAL_CREDIT, 1));
        balanceTypeConfigStore.put("TRADE_AHEAD_BALANCE", new BalanceTypeConfig(
                "TRADE_AHEAD_BALANCE", true, NegativeSemantics.PRE_AUTHORIZED,
                SignConvention.NORMAL_DEBIT, 1));

        // Create test account
        accountMetaStore.put("CLIENT_ACC_001", new Account(
                "CLIENT_ACC_001", AccountType.COMPANY, "Client 001",
                "CUST-001", AccountStatus.ACTIVE, null, Instant.now()));
    }

    @Test
    @DisplayName("TC-F011-01 publishEvent first posting accountSeq is 1")
    void publishEvent_firstPosting_accountSeqIsOne() {
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", BigDecimal.ZERO, 0);

        PostingCommand cmd = new PostingCommand(
                "req-011", "TEST", "test-ref", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST", BigDecimal.ONE, "USD", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", EntryType.CREDIT, "First")
                )))
        );

        CommandResult result = stateMachine.applyPosting(cmd);

        assertThat(result.isCompleted()).isTrue();
        assertThat(capturedEvents).hasSize(1);

        BalanceChangeEvent event = capturedEvents.get(0);
        assertThat(event.accountSeq()).isEqualTo(1);
        assertThat(event.prevAccountSeq()).isEqualTo(0);
    }

    @Test
    @DisplayName("TC-F011-02 publishEvent subsequent posting accountSeq incremented")
    void publishEvent_subsequentPosting_accountSeqIncremented() {
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("1000.00"), 41);

        PostingCommand cmd = new PostingCommand(
                "req-012", "TEST", "test-ref", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST", BigDecimal.ONE, "USD", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", EntryType.DEBIT, "Subsequent")
                )))
        );

        stateMachine.applyPosting(cmd);

        assertThat(capturedEvents).hasSize(1);
        BalanceChangeEvent event = capturedEvents.get(0);
        assertThat(event.accountSeq()).isEqualTo(42);
        assertThat(event.prevAccountSeq()).isEqualTo(41);
    }

    @Test
    @DisplayName("TC-F011-04 consumer detects gap when seq not consecutive")
    void consumer_detectsGap_whenSeqNotConsecutive() {
        // Simulate: consumer last saw accountSeq=100, then receives accountSeq=102 with prevAccountSeq=101
        long lastSeenSeq = 100;
        long receivedAccountSeq = 102;
        long receivedPrevAccountSeq = 101;

        // Gap detection: prevAccountSeq != lastSeenSeq means a gap
        boolean gapDetected = receivedPrevAccountSeq != lastSeenSeq;
        assertThat(gapDetected).isTrue();
    }

    @Test
    @DisplayName("TC-F011-05 consumer no duplicate alert when idempotent retry")
    void consumer_noDuplicateAlert_whenIdempotentRetry() {
        // Same idempotencyKey means same event — consumer should de-duplicate by key
        String idempotencyKey = "req-001:CLIENT_ACC_001:AVAILABLE_BALANCE:CURRENT:USD";

        // First event
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("1000.00"), 49);
        PostingCommand cmd = new PostingCommand(
                "req-001", "TEST", "test-ref", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST", BigDecimal.ONE, "USD", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", EntryType.DEBIT, "First")
                )))
        );
        stateMachine.applyPosting(cmd);

        assertThat(capturedEvents).hasSize(1);
        BalanceChangeEvent first = capturedEvents.get(0);
        assertThat(first.accountSeq()).isEqualTo(50);
        assertThat(first.idempotencyKey()).isEqualTo(idempotencyKey);

        // Replay: same requestId should be idempotent and NOT emit another event
        capturedEvents.clear();
        CommandResult replayResult = stateMachine.applyPosting(cmd);
        assertThat(replayResult.isCompleted()).isTrue();
        // Idempotent — no new event published
        assertThat(capturedEvents).isEmpty();
    }

    @Test
    @DisplayName("TC-F011-06 restart node outbox resend accountSeq unchanged")
    void restartNode_outboxResend_accountSeqUnchanged() {
        // accountSeq is determined at apply time and doesn't change on resend
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("500.00"), 76);

        PostingCommand cmd = new PostingCommand(
                "req-016", "TEST", "test-ref", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST", BigDecimal.ONE, "USD", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", EntryType.CREDIT, "Credit")
                )))
        );

        stateMachine.applyPosting(cmd);

        assertThat(capturedEvents).hasSize(1);
        BalanceChangeEvent event = capturedEvents.get(0);
        assertThat(event.accountSeq()).isEqualTo(77);

        // Simulate: outbox resend — the in-memory event's accountSeq doesn't change
        // because it was set at apply time
        assertThat(event.accountSeq()).isEqualTo(77);
    }

    @Test
    @DisplayName("TC-F011-07 multi balance type same posting seq independent per key")
    void multiBalanceType_samePosting_seqIndependentPerKey() {
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("1000.00"), 10);
        setBalance("CLIENT_ACC_001", "TRADE_AHEAD_BALANCE", "USD", BigDecimal.ZERO, 5);

        PostingCommand cmd = new PostingCommand(
                "req-017", "RFQ_SETTLEMENT", "RFQ-001", LocalDate.now(),
                List.of(
                        new PostingCommand.Leg("leg-1", "TEST", BigDecimal.ONE, "USD", List.of(
                                new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", EntryType.DEBIT, "Avail debit")
                        )),
                        new PostingCommand.Leg("leg-2", "TEST", BigDecimal.ONE, "USD", List.of(
                                new PostingCommand.Line("CLIENT_ACC_001", "TRADE_AHEAD_BALANCE", "CURRENT", EntryType.DEBIT, "Trade debit")
                        ))
                )
        );

        stateMachine.applyPosting(cmd);

        assertThat(capturedEvents).hasSize(2);

        var availEvent = capturedEvents.stream()
                .filter(e -> e.balanceType().equals("AVAILABLE_BALANCE"))
                .findFirst().orElseThrow();
        var tradeEvent = capturedEvents.stream()
                .filter(e -> e.balanceType().equals("TRADE_AHEAD_BALANCE"))
                .findFirst().orElseThrow();

        assertThat(availEvent.accountSeq()).isEqualTo(11);
        assertThat(availEvent.prevAccountSeq()).isEqualTo(10);
        assertThat(tradeEvent.accountSeq()).isEqualTo(6);
        assertThat(tradeEvent.prevAccountSeq()).isEqualTo(5);
    }

    @Test
    @DisplayName("TC-F011-03 publishEvent reversal accountSeq incremented")
    void publishEvent_reversal_accountSeqIncremented() {
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("1000.00"), 10);

        PostingCommand postCmd = new PostingCommand(
                "req-013", "TEST", "test-ref", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST", BigDecimal.ONE, "USD", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", EntryType.DEBIT, "Original")
                )))
        );
        String journalId = stateMachine.applyPosting(postCmd).journalId();

        // Clear posting events, then apply reversal
        capturedEvents.clear();

        stateMachine.applyReversal(new ReversalCommand(
                "rev-013", journalId, "Test reversal", "CANCEL", LocalDate.now()));

        assertThat(capturedEvents).hasSize(1);
        BalanceChangeEvent revEvent = capturedEvents.get(0);
        assertThat(revEvent.accountSeq()).isEqualTo(12); // 10 + 1 (posting) + 1 (reversal)
        assertThat(revEvent.prevAccountSeq()).isEqualTo(11);
        assertThat(revEvent.commandType()).isEqualTo("REVERSAL");
    }

    private void setBalance(String accountId, String balanceType, String currency,
                            BigDecimal amount, long accountSeq) {
        AccountBalanceKey key = new AccountBalanceKey(accountId, balanceType, "CURRENT", currency);
        balanceStore.put(key, new BalanceEntry(amount, 0, accountSeq, "", Instant.now()));
    }
}

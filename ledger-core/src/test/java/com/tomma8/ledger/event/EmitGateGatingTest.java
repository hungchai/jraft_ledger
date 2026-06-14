package com.tomma8.ledger.event;

import com.tomma8.ledger.domain.command.AccountCreateCommand;
import com.tomma8.ledger.domain.command.PostingCommand;
import com.tomma8.ledger.domain.event.AccountCreatedEvent;
import com.tomma8.ledger.domain.event.BalanceChangeEvent;
import com.tomma8.ledger.domain.event.LedgerEventListener;
import com.tomma8.ledger.domain.model.*;
import com.tomma8.ledger.rocksdb.OutboxStore;
import com.tomma8.ledger.rocksdb.RocksDBManager;
import com.tomma8.ledger.statemachine.LedgerStateMachine;
import com.tomma8.ledger.store.AccountMetaStore;
import com.tomma8.ledger.store.BalanceStore;
import com.tomma8.ledger.store.BalanceTypeConfigStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the EmitGate blocks Kafka emission at the two emit sites:
 *   1. LedgerStateMachine applyPosting / applyAccountCreate / applyReversal
 *   2. AsyncOutboxPublisher.scanAndPublish
 *
 * Gate closed → no eventListener calls, no outbox publish.
 * Gate opened → events flow as before.
 */
@DisplayName("EmitGate gating — Kafka emission during init")
class EmitGateGatingTest {

    private RocksDBManager rocksDBManager;
    private OutboxStore outboxStore;
    private BalanceStore balanceStore;
    private AccountMetaStore accountMetaStore;
    private BalanceTypeConfigStore balanceTypeConfigStore;
    private LedgerStateMachine stateMachine;
    private EmitGate emitGate;
    private CountingListener listener;

    @BeforeEach
    void setUp() throws Exception {
        String dbPath = System.getProperty("java.io.tmpdir") + "/emit-gate-test-" + UUID.randomUUID();
        rocksDBManager = new RocksDBManager(dbPath);
        rocksDBManager.open();
        outboxStore = new OutboxStore(rocksDBManager);

        balanceStore = new BalanceStore();
        accountMetaStore = new AccountMetaStore();
        balanceTypeConfigStore = new BalanceTypeConfigStore();
        stateMachine = new LedgerStateMachine(balanceStore, accountMetaStore, balanceTypeConfigStore);

        emitGate = stateMachine.getEmitGate();
        assertThat(emitGate.isEnabled()).as("gate starts closed").isFalse();

        balanceTypeConfigStore.put("AVAILABLE_BALANCE", new BalanceTypeConfig(
                "AVAILABLE_BALANCE", false, null, SignConvention.NORMAL_CREDIT, 1));
        accountMetaStore.put("CLIENT_ACC_001", new Account(
                "CLIENT_ACC_001", AccountType.COMPANY, "Client",
                "CUST-001", AccountStatus.ACTIVE, null, Instant.now()));
        balanceStore.put(new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", "USD"),
                new BalanceEntry(new BigDecimal("1000.00"), 0, 1, "", Instant.now()));

        listener = new CountingListener();
        stateMachine.setEventListener(listener);
    }

    @AfterEach
    void tearDown() {
        if (rocksDBManager != null) rocksDBManager.close();
    }

    @Test
    @DisplayName("TC-EMIT-01 gate closed: applyPosting does NOT call eventListener")
    void gateClosed_applyPosting_doesNotEmit() {
        PostingCommand cmd = new PostingCommand(
                "emit-001", "TEST", "emit-ref", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST_TYPE", new BigDecimal("100.00"), "USD", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", EntryType.DEBIT, "Test")
                )))
        );

        stateMachine.applyPosting(cmd);

        assertThat(listener.balanceEvents.get()).isZero();
    }

    @Test
    @DisplayName("TC-EMIT-02 gate open: applyPosting fires onPosting envelope (not per-event)")
    void gateOpen_applyPosting_emits() {
        emitGate.setEnabled(true);

        PostingCommand cmd = new PostingCommand(
                "emit-002", "TEST", "emit-ref", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST_TYPE", new BigDecimal("100.00"), "USD", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", EntryType.DEBIT, "Test")
                )))
        );

        stateMachine.applyPosting(cmd);

        // v0.3 envelope: 1 envelope call, NOT 1 per-event call
        assertThat(listener.envelopes.get()).isEqualTo(1);
        assertThat(listener.balanceEvents.get()).isZero();
    }

    @Test
    @DisplayName("TC-EMIT-03 gate closed: applyAccountCreate does NOT call onAccountCreated")
    void gateClosed_applyAccountCreate_doesNotEmit() {
        AccountCreateCommand cmd = new AccountCreateCommand(
                "req-bootstrap", "NEW_ACC_001", AccountType.CLIENT, "New", "OWN-001",
                List.of(new AccountCreateCommand.BalanceInitialization("AVAILABLE_BALANCE", "USD")));

        stateMachine.applyAccountCreate(cmd);

        assertThat(listener.accountEvents.get()).isZero();
    }

    @Test
    @DisplayName("TC-EMIT-04 gate open: applyAccountCreate DOES call onAccountCreated")
    void gateOpen_applyAccountCreate_emits() {
        emitGate.setEnabled(true);

        AccountCreateCommand cmd = new AccountCreateCommand(
                "req-bootstrap-2", "NEW_ACC_002", AccountType.CLIENT, "New 2", "OWN-002",
                List.of(new AccountCreateCommand.BalanceInitialization("AVAILABLE_BALANCE", "USD")));

        stateMachine.applyAccountCreate(cmd);

        assertThat(listener.accountEvents.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-EMIT-05 gate closed: AsyncOutboxPublisher.scanAndPublish is a no-op")
    void gateClosed_outboxPublisher_skipsScan() throws Exception {
        // Seed CF_OUTBOX with a journal envelope so there's something to publish if gate were open
        BalanceChangeEvent ev1 = new BalanceChangeEvent(
                "evt-gate-001-a", "BALANCE_CHANGE", "1.2", Instant.now(),
                "req-gate-001", "POSTING", "JNL-0001", "JL-01",
                "req-gate-001", "BEV-001", null,
                "CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", "USD",
                EntryType.DEBIT, BigDecimal.ONE,
                new BigDecimal("1000.00"), new BigDecimal("999.00"),
                BigDecimal.ONE.negate(),
                1L, 1L, 2L, 1L,
                LocalDate.now(), LocalDate.now(),
                Map.of("sourceSystem", "LEDGER"));
        com.tomma8.ledger.domain.event.JournalEventEnvelope env =
                new com.tomma8.ledger.domain.event.JournalEventEnvelope(
                        com.tomma8.ledger.domain.event.JournalEventEnvelope.TYPE,
                        "JNL-0001", List.of(ev1));
        com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper()
                .findAndRegisterModules();
        outboxStore.enqueueJournal("JNL-0001", m.writeValueAsBytes(env));
        assertThat(outboxStore.readPendingJournals(10)).hasSize(1);

        CountingKafkaPublisher kafkaPublisher = new CountingKafkaPublisher();
        AsyncOutboxPublisher asyncPublisher = new AsyncOutboxPublisher(
                outboxStore, kafkaPublisher, emitGate, Duration.ofDays(1), 100);

        // Gate is still closed → trigger scan
        Method scanMethod = AsyncOutboxPublisher.class.getDeclaredMethod("scanAndPublish");
        scanMethod.setAccessible(true);
        scanMethod.invoke(asyncPublisher);

        assertThat(kafkaPublisher.onPostingCalls.get())
                .as("publisher must not be called while gate is closed")
                .isZero();

        // Outbox entry should still be there (publisher never ran)
        assertThat(outboxStore.readPendingJournals(10)).hasSize(1);

        // Flip gate open → next scan publishes
        emitGate.setEnabled(true);
        scanMethod.invoke(asyncPublisher);

        assertThat(kafkaPublisher.onPostingCalls.get())
                .as("publisher must be called after gate is opened")
                .isEqualTo(1);

        asyncPublisher.close();
    }

    @Test
    @DisplayName("TC-EMIT-07 gate closed: persistApply skips outbox CF write")
    void gateClosed_persistApply_skipsOutboxWrite() {
        // Enable persistApply with real RocksDB + outbox (default in production)
        stateMachine.setRocksDB(rocksDBManager);
        stateMachine.setOutboxStore(outboxStore);
        stateMachine.setPersistAfterApply(true);
        // Gate stays closed

        PostingCommand cmd = new PostingCommand(
                "emit-007", "TEST", "emit-ref", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST_TYPE", new BigDecimal("25.00"), "USD", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", EntryType.DEBIT, "Test")
                )))
        );
        stateMachine.applyPosting(cmd);

        assertThat(listener.balanceEvents.get()).isZero();
        assertThat(outboxStore.readPending(100))
                .as("follower (gate closed) must NOT write to CF_OUTBOX")
                .isEmpty();
    }

    @Test
    @DisplayName("TC-EMIT-08 gate open: persistApply writes to outbox CF")
    void gateOpen_persistApply_writesOutbox() {
        stateMachine.setRocksDB(rocksDBManager);
        stateMachine.setOutboxStore(outboxStore);
        stateMachine.setPersistAfterApply(true);
        emitGate.setEnabled(true);

        PostingCommand cmd = new PostingCommand(
                "emit-008", "TEST", "emit-ref", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST_TYPE", new BigDecimal("25.00"), "USD", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", EntryType.DEBIT, "Test")
                )))
        );
        stateMachine.applyPosting(cmd);

        assertThat(listener.envelopes.get()).isEqualTo(1);
        assertThat(outboxStore.readPendingJournals(100))
                .as("leader (gate open) MUST write 1 envelope per journal to CF_OUTBOX")
                .hasSize(1);
    }

    // ── Test fixtures ──────────────────────────────────────────────

    static class CountingListener implements LedgerEventListener {
        final AtomicInteger balanceEvents = new AtomicInteger(0);
        final AtomicInteger accountEvents = new AtomicInteger(0);
        final AtomicInteger envelopes = new AtomicInteger(0);
        final List<BalanceChangeEvent> balanceSeen = new ArrayList<>();
        final List<AccountCreatedEvent> accountSeen = new ArrayList<>();
        final List<com.tomma8.ledger.domain.event.JournalEventEnvelope> envelopeSeen = new ArrayList<>();

        @Override
        public void onEvent(BalanceChangeEvent event) {
            balanceEvents.incrementAndGet();
            balanceSeen.add(event);
        }

        @Override
        public void onPosting(com.tomma8.ledger.domain.event.JournalEventEnvelope envelope) {
            envelopes.incrementAndGet();
            envelopeSeen.add(envelope);
        }

        @Override
        public void onAccountCreated(AccountCreatedEvent event) {
            accountEvents.incrementAndGet();
            accountSeen.add(event);
        }
    }

    static class CountingKafkaPublisher extends KafkaEventPublisher {
        final AtomicInteger calls = new AtomicInteger(0);
        final AtomicInteger onPostingCalls = new AtomicInteger(0);
        CountingKafkaPublisher() { super("localhost:9999", "fake-topic"); }
        @Override
        public void onEvent(BalanceChangeEvent event) { calls.incrementAndGet(); }
        @Override
        public void onPosting(com.tomma8.ledger.domain.event.JournalEventEnvelope envelope) { onPostingCalls.incrementAndGet(); }
    }
}

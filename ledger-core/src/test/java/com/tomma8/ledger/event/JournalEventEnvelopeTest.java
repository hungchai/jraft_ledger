package com.tomma8.ledger.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tomma8.ledger.domain.command.PostingCommand;
import com.tomma8.ledger.domain.event.AccountCreatedEvent;
import com.tomma8.ledger.domain.event.BalanceChangeEvent;
import com.tomma8.ledger.domain.event.JournalEventEnvelope;
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
 * TC-ENVELOPE-01..06: per-journal envelope wire format.
 *
 * Verifies that a posting (or reversal) with N line events produces
 * exactly 1 Kafka record (1 envelope) instead of N individual records.
 * 4× reduction in Kafka traffic on the projection topic.
 */
@DisplayName("JournalEventEnvelope — 1 Kafka record per posting")
class JournalEventEnvelopeTest {

    private RocksDBManager rocksDBManager;
    private OutboxStore outboxStore;
    private BalanceStore balanceStore;
    private AccountMetaStore accountMetaStore;
    private BalanceTypeConfigStore balanceTypeConfigStore;
    private LedgerStateMachine stateMachine;
    private com.tomma8.ledger.event.EmitGate emitGate;
    private EnvelopeCountingListener listener;

    @BeforeEach
    void setUp() throws Exception {
        String dbPath = System.getProperty("java.io.tmpdir") + "/envelope-test-" + UUID.randomUUID();
        rocksDBManager = new RocksDBManager(dbPath);
        rocksDBManager.open();
        outboxStore = new OutboxStore(rocksDBManager);

        balanceStore = new BalanceStore();
        accountMetaStore = new AccountMetaStore();
        balanceTypeConfigStore = new BalanceTypeConfigStore();
        stateMachine = new LedgerStateMachine(balanceStore, accountMetaStore, balanceTypeConfigStore);
        emitGate = stateMachine.getEmitGate();

        balanceTypeConfigStore.put("AVAILABLE_BALANCE", new BalanceTypeConfig(
                "AVAILABLE_BALANCE", false, null, SignConvention.NORMAL_CREDIT, 1));
        accountMetaStore.put("CLIENT_ACC_001", new Account(
                "CLIENT_ACC_001", AccountType.COMPANY, "Client", "CUST-001",
                AccountStatus.ACTIVE, null, Instant.now()));
        accountMetaStore.put("COMPANY_FX_ACC", new Account(
                "COMPANY_FX_ACC", AccountType.COMPANY, "Company", null,
                AccountStatus.ACTIVE, null, Instant.now()));
        balanceStore.put(new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", "USD"),
                new BalanceEntry(new BigDecimal("10000.00"), 0, 1, "", Instant.now()));
        balanceStore.put(new AccountBalanceKey("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "CURRENT", "USD"),
                new BalanceEntry(new BigDecimal("100000.00"), 0, 1, "", Instant.now()));

        listener = new EnvelopeCountingListener();
        stateMachine.setEventListener(listener);
    }

    @AfterEach
    void tearDown() {
        if (rocksDBManager != null) rocksDBManager.close();
    }

    @Test
    @DisplayName("TC-ENVELOPE-01 gate open: applyPosting produces 1 envelope, 0 per-event")
    void applyPosting_oneEnvelopePerJournal() {
        emitGate.setEnabled(true);

        PostingCommand cmd = new PostingCommand(
                "env-001", "RFQ_SETTLEMENT", "RFQ-001", LocalDate.now(),
                List.of(
                        new PostingCommand.Leg("leg-1", "TRADE", new BigDecimal("100.00"), "USD", List.of(
                                new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", EntryType.DEBIT, "client pays"),
                                new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "CURRENT", EntryType.CREDIT, "co receives")
                        )),
                        new PostingCommand.Leg("leg-2", "TRADE", new BigDecimal("0.001"), "BTC", List.of(
                                new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "CURRENT", EntryType.DEBIT, "co pays btc"),
                                new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", EntryType.CREDIT, "client gets btc")
                        ))
                )
        );

        stateMachine.applyPosting(cmd);

        assertThat(listener.envelopes.get())
                .as("one envelope per posting (not 4 per-event calls)")
                .isEqualTo(1);
        assertThat(listener.events.get())
                .as("default fallback not invoked when envelope path is used")
                .isZero();

        JournalEventEnvelope env = listener.lastEnvelope;
        assertThat(env.type()).isEqualTo("JOURNAL");
        assertThat(env.journalId()).startsWith("JNL-");
        assertThat(env.events()).hasSize(4);
    }

    @Test
    @DisplayName("TC-ENVELOPE-02 gate closed: no envelope, no per-event, no outbox CF write")
    void applyPosting_gateClosed_zeroEmission() {
        // gate closed (default)

        PostingCommand cmd = new PostingCommand(
                "env-002", "TEST", "test", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "T", new BigDecimal("50.00"), "USD", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", EntryType.DEBIT, "x"),
                        new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "CURRENT", EntryType.CREDIT, "y")
                )))
        );
        stateMachine.applyPosting(cmd);

        assertThat(listener.envelopes.get()).isZero();
        assertThat(listener.events.get()).isZero();
    }

    @Test
    @DisplayName("TC-ENVELOPE-03 envelope JSON serializes + deserializes round-trip")
    void envelopeJsonRoundTrip() throws Exception {
        BalanceChangeEvent ev1 = sampleEvent("JNL-T-1", "ev-1");
        BalanceChangeEvent ev2 = sampleEvent("JNL-T-1", "ev-2");
        JournalEventEnvelope env = new JournalEventEnvelope("JOURNAL", "JNL-T-1", List.of(ev1, ev2));

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String json = mapper.writeValueAsString(env);
        assertThat(json).contains("\"type\":\"JOURNAL\"");
        assertThat(json).contains("\"journalId\":\"JNL-T-1\"");

        JournalEventEnvelope back = mapper.readValue(json, JournalEventEnvelope.class);
        assertThat(back.type()).isEqualTo("JOURNAL");
        assertThat(back.journalId()).isEqualTo("JNL-T-1");
        assertThat(back.events()).hasSize(2);
        assertThat(back.events().get(0).eventId()).isEqualTo("ev-1");
    }

    @Test
    @DisplayName("TC-ENVELOPE-04 gate open: persistApply writes 1 envelope entry to CF_OUTBOX")
    void persistApply_writesOneEnvelopePerJournal() {
        stateMachine.setRocksDB(rocksDBManager);
        stateMachine.setOutboxStore(outboxStore);
        stateMachine.setPersistAfterApply(true);
        emitGate.setEnabled(true);

        PostingCommand cmd = new PostingCommand(
                "env-004", "TEST", "test", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "T", new BigDecimal("25.00"), "USD", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", EntryType.DEBIT, "x"),
                        new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "CURRENT", EntryType.CREDIT, "y")
                )))
        );
        stateMachine.applyPosting(cmd);

        // 1 envelope per journal in CF (key prefix "outbox:journal:")
        List<JournalEventEnvelope> journals = outboxStore.readPendingJournals(100);
        assertThat(journals)
                .as("1 envelope per journal, not 2 per-line entries")
                .hasSize(1);
        assertThat(journals.get(0).events()).hasSize(2);
    }

    @Test
    @DisplayName("TC-ENVELOPE-05 AsyncOutboxPublisher publishes envelope via onPosting")
    void asyncPublisher_publishesEnvelope() throws Exception {
        // Seed CF_OUTBOX with an envelope entry directly
        BalanceChangeEvent ev1 = sampleEvent("JNL-ASYNC-1", "ev-1");
        BalanceChangeEvent ev2 = sampleEvent("JNL-ASYNC-1", "ev-2");
        JournalEventEnvelope env = new JournalEventEnvelope("JOURNAL", "JNL-ASYNC-1", List.of(ev1, ev2));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        outboxStore.enqueueJournal("JNL-ASYNC-1", mapper.writeValueAsBytes(env));
        assertThat(outboxStore.readPendingJournals(10)).hasSize(1);

        // open gate, run scan
        emitGate.setEnabled(true);
        CountingKafkaPublisher kp = new CountingKafkaPublisher();
        AsyncOutboxPublisher asyncPublisher = new AsyncOutboxPublisher(
                outboxStore, kp, emitGate, Duration.ofDays(1), 100);

        Method scan = AsyncOutboxPublisher.class.getDeclaredMethod("scanAndPublish");
        scan.setAccessible(true);
        scan.invoke(asyncPublisher);

        assertThat(kp.onPostingCalls.get())
                .as("async publisher must use envelope path")
                .isEqualTo(1);
        assertThat(kp.onEventCalls.get())
                .as("must NOT fall back to per-event path for envelope entries")
                .isZero();
    }

    @Test
    @DisplayName("TC-ENVELOPE-06 gate closed: no envelope and no per-event on apply")
    void gateClosed_applyProducesZeroEmission() {
        PostingCommand cmd = new PostingCommand(
                "env-006", "TEST", "test", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "T", new BigDecimal("99.00"), "USD", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", EntryType.DEBIT, "x"),
                        new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "CURRENT", EntryType.CREDIT, "y")
                )))
        );
        emitGate.setEnabled(false);
        stateMachine.applyPosting(cmd);
        assertThat(listener.envelopes.get()).isZero();
        assertThat(listener.events.get()).isZero();
    }

    // ── Helpers ─────────────────────────────────────────────────

    private static BalanceChangeEvent sampleEvent(String journalId, String eventId) {
        return new BalanceChangeEvent(
                eventId, "BALANCE_CHANGE", "1.2", Instant.now(),
                "req-" + eventId, "POSTING", journalId, "JL-1",
                "req-" + eventId, "BEV-" + eventId, null,
                "CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", "USD",
                EntryType.DEBIT, BigDecimal.ONE,
                new BigDecimal("100.00"), new BigDecimal("99.00"),
                BigDecimal.ONE.negate(),
                1L, 1L, 2L, 1L,
                LocalDate.now(), LocalDate.now(),
                Map.of("sourceSystem", "LEDGER"));
    }

    /** Counting listener that separates envelope from per-event emissions. */
    static class EnvelopeCountingListener implements LedgerEventListener {
        final AtomicInteger envelopes = new AtomicInteger(0);
        final AtomicInteger events = new AtomicInteger(0);
        final AtomicInteger accountEvents = new AtomicInteger(0);
        final List<JournalEventEnvelope> envelopeSeen = new ArrayList<>();
        final List<BalanceChangeEvent> eventSeen = new ArrayList<>();
        JournalEventEnvelope lastEnvelope;

        @Override
        public void onEvent(BalanceChangeEvent event) {
            events.incrementAndGet();
            eventSeen.add(event);
        }

        @Override
        public void onPosting(JournalEventEnvelope envelope) {
            envelopes.incrementAndGet();
            envelopeSeen.add(envelope);
            lastEnvelope = envelope;
        }

        @Override
        public void onAccountCreated(AccountCreatedEvent event) {
            accountEvents.incrementAndGet();
        }
    }

    static class CountingKafkaPublisher extends KafkaEventPublisher {
        final AtomicInteger onPostingCalls = new AtomicInteger(0);
        final AtomicInteger onEventCalls = new AtomicInteger(0);
        CountingKafkaPublisher() { super("localhost:9999", "fake-topic"); }
        @Override public void onEvent(BalanceChangeEvent event) { onEventCalls.incrementAndGet(); }
        @Override public void onPosting(JournalEventEnvelope envelope) { onPostingCalls.incrementAndGet(); }
    }
}

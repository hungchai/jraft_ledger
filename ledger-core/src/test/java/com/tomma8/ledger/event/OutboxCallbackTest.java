package com.tomma8.ledger.event;

import com.tomma8.ledger.domain.command.PostingCommand;
import com.tomma8.ledger.domain.event.BalanceChangeEvent;
import com.tomma8.ledger.domain.model.*;
import com.tomma8.ledger.rocksdb.OutboxStore;
import com.tomma8.ledger.rocksdb.RocksDBManager;
import com.tomma8.ledger.statemachine.LedgerStateMachine;
import com.tomma8.ledger.store.AccountMetaStore;
import com.tomma8.ledger.store.BalanceStore;
import com.tomma8.ledger.store.BalanceTypeConfigStore;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * TC-F011-10 ~ TC-F011-12: Transactional outbox callback-driven deletion.
 */
@Testcontainers
@DisplayName("Outbox Callback-Driven Deletion")
class OutboxCallbackTest {

    @Container
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    private RocksDBManager rocksDBManager;
    private OutboxStore outboxStore;
    private BalanceStore balanceStore;
    private AccountMetaStore accountMetaStore;
    private BalanceTypeConfigStore balanceTypeConfigStore;
    private LedgerStateMachine stateMachine;

    @BeforeEach
    void setUp() throws Exception {
        String dbPath = System.getProperty("java.io.tmpdir") + "/outbox-callback-test-" + UUID.randomUUID();
        rocksDBManager = new RocksDBManager(dbPath);
        rocksDBManager.open();
        outboxStore = new OutboxStore(rocksDBManager);

        balanceStore = new BalanceStore();
        accountMetaStore = new AccountMetaStore();
        balanceTypeConfigStore = new BalanceTypeConfigStore();
        stateMachine = new LedgerStateMachine(balanceStore, accountMetaStore, balanceTypeConfigStore);

        balanceTypeConfigStore.put("AVAILABLE_BALANCE", new BalanceTypeConfig(
                "AVAILABLE_BALANCE", false, null, SignConvention.NORMAL_CREDIT, 1));
        accountMetaStore.put("CLIENT_ACC_001", new Account(
                "CLIENT_ACC_001", AccountType.COMPANY, "Client",
                "CUST-001", AccountStatus.ACTIVE, null, Instant.now()));
        balanceStore.put(new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", "USD"),
                new BalanceEntry(new BigDecimal("1000.00"), 0, 1, "", Instant.now()));
    }

    @AfterEach
    void tearDown() {
        if (rocksDBManager != null) rocksDBManager.close();
    }

    @Test
    @DisplayName("TC-F011-10 kafkaPublisher callback deletes outbox on Kafka ack")
    void kafkaPublisher_callback_deletesOutbox_onSuccess() throws Exception {
        String topic = "ledger.balance.change.outbox-test-" + UUID.randomUUID().toString().substring(0, 8);
        KafkaEventPublisher publisher = new KafkaEventPublisher(kafka.getBootstrapServers(), topic);
        publisher.setOutboxStore(outboxStore);

        // Seed outbox manually (simulates residual after crash)
        BalanceChangeEvent event = new BalanceChangeEvent(
                "evt-residual-001", "BALANCE_CHANGE", "1.2", Instant.now(),
                "req-residual-001", "POSTING", "JNL-0001", "JL-01",
                "req-residual-001", "BEV-001", null,
                "CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", "USD",
                EntryType.DEBIT, BigDecimal.ONE,
                new BigDecimal("1000.00"), new BigDecimal("999.00"),
                BigDecimal.ONE.negate(),
                1L, 1L, 2L, 1L,
                LocalDate.now(), LocalDate.now(),
                Map.of("sourceSystem", "LEDGER"));

        outboxStore.enqueue(event);
        outboxStore.flush();
        assertThat(outboxStore.readPending(10)).hasSize(1);

        // Publish from outbox (simulating AsyncOutboxPublisher path)
        publisher.onEvent(event);
        publisher.flush(); // blocks until callbacks complete

        // Callback should have deleted the outbox entry
        List<BalanceChangeEvent> pendingAfter = outboxStore.readPending(10);
        assertThat(pendingAfter).isEmpty();

        publisher.close();
    }

    @Test
    @DisplayName("TC-F011-11 AsyncOutboxPublisher does not call markSent directly")
    void asyncOutboxPublisher_doesNotDelete_directly() throws Exception {
        // Seed outbox with an event manually
        BalanceChangeEvent event = new BalanceChangeEvent(
                "evt-test-001", "BALANCE_CHANGE", "1.2", Instant.now(),
                "req-test-001", "POSTING", "JNL-0001", "JL-01",
                "req-test-001", "BEV-001", null,
                "CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", "USD",
                EntryType.DEBIT, BigDecimal.ONE,
                new BigDecimal("1000.00"), new BigDecimal("999.00"),
                BigDecimal.ONE.negate(),
                1L, 1L, 2L, 1L,
                LocalDate.now(), LocalDate.now(),
                Map.of("sourceSystem", "LEDGER"));

        outboxStore.enqueue(event);
        outboxStore.flush();
        assertThat(outboxStore.readPending(10)).hasSize(1);

        // Fake publisher that does NOT call markSent (simulating old broken path)
        KafkaEventPublisher fakePublisher = new FakeKafkaEventPublisher();

        AsyncOutboxPublisher asyncPublisher = new AsyncOutboxPublisher(
                outboxStore, fakePublisher, Duration.ofDays(1), 100);

        // Trigger scan manually via reflection
        Method scanMethod = AsyncOutboxPublisher.class.getDeclaredMethod("scanAndPublish");
        scanMethod.setAccessible(true);
        scanMethod.invoke(asyncPublisher);

        // Because fakePublisher never acks, and AsyncOutboxPublisher no longer
        // calls markSent directly, the event must remain in outbox.
        List<BalanceChangeEvent> pendingAfter = outboxStore.readPending(10);
        assertThat(pendingAfter).hasSize(1);

        asyncPublisher.close();
    }

    @Test
    @DisplayName("TC-F011-12 hotPath publish callback deletes outbox immediately")
    void hotPath_callback_deletesOutboxImmediately() throws Exception {
        String topic = "ledger.balance.change.hotpath-test-" + UUID.randomUUID().toString().substring(0, 8);
        KafkaEventPublisher publisher = new KafkaEventPublisher(kafka.getBootstrapServers(), topic);
        publisher.setOutboxStore(outboxStore);
        stateMachine.setEventListener(publisher);
        stateMachine.setOutboxStore(outboxStore);
        stateMachine.setPersistAfterApply(false);
        // EmitGate is closed by default; tests opt in.
        stateMachine.getEmitGate().setEnabled(true);

        PostingCommand cmd = new PostingCommand(
                "hotpath-cb-001", "TEST", "HOTPATH-CB-001", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST", BigDecimal.ONE, "USD", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", EntryType.DEBIT, "Hot path callback test")
                )))
        );
        stateMachine.applyPosting(cmd);

        // StateMachine calls eventListener.onEvent(event) then outboxStore.enqueue(event)
        // The async callback should eventually delete from outbox.
        publisher.flush();

        // After flush, callback has run and outbox should be empty
        List<BalanceChangeEvent> pendingAfter = outboxStore.readPending(10);
        assertThat(pendingAfter).isEmpty();

        publisher.close();
    }

    /**
     * Fake publisher that does nothing — used to simulate a publisher whose
     * callback never calls markSent.
     */
    static class FakeKafkaEventPublisher extends KafkaEventPublisher {
        FakeKafkaEventPublisher() {
            super("localhost:9999", "fake-topic");
        }

        @Override
        public void onEvent(BalanceChangeEvent event) {
            // no-op
        }
    }
}

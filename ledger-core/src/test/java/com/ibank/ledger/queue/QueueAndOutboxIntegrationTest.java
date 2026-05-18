package com.ibank.ledger.queue;

import com.ibank.ledger.domain.command.PostingCommand;
import com.ibank.ledger.domain.event.BalanceChangeEvent;
import com.ibank.ledger.domain.model.*;
import com.ibank.ledger.rocksdb.OutboxStore;
import com.ibank.ledger.statemachine.LedgerStateMachine;
import com.ibank.ledger.store.AccountMetaStore;
import com.ibank.ledger.store.BalanceStore;
import com.ibank.ledger.store.BalanceTypeConfigStore;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Account Queue + Outbox Integration")
class QueueAndOutboxIntegrationTest {

    private BalanceStore balanceStore;
    private AccountMetaStore accountMetaStore;
    private BalanceTypeConfigStore balanceTypeConfigStore;
    private LedgerStateMachine stateMachine;
    private AccountQueueManager queueManager;
    private List<BalanceChangeEvent> outboxEvents;

    @BeforeEach
    void setUp() {
        balanceStore = new BalanceStore();
        accountMetaStore = new AccountMetaStore();
        balanceTypeConfigStore = new BalanceTypeConfigStore();
        stateMachine = new LedgerStateMachine(balanceStore, accountMetaStore, balanceTypeConfigStore);
        outboxEvents = new ArrayList<>();

        balanceTypeConfigStore.put("AVAILABLE_BALANCE", new BalanceTypeConfig(
                "AVAILABLE_BALANCE", false, null, SignConvention.NORMAL_CREDIT, 1));
        accountMetaStore.put("CLIENT_ACC_001", new Account(
                "CLIENT_ACC_001", AccountType.CLIENT, "Client",
                "CUST-001", AccountStatus.ACTIVE, null, Instant.now()));
        accountMetaStore.put("COMPANY_FX_ACC", new Account(
                "COMPANY_FX_ACC", AccountType.COMPANY, "Company",
                null, AccountStatus.ACTIVE, null, Instant.now()));
        balanceStore.put(new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD"),
                new BalanceEntry(new BigDecimal("10000.00"), 0, 1, "", Instant.now()));
        balanceStore.put(new AccountBalanceKey("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD"),
                new BalanceEntry(new BigDecimal("100000.00"), 0, 1, "", Instant.now()));

        // Wire event listener to capture events (simulating outbox)
        stateMachine.setEventListener(outboxEvents::add);

        queueManager = new AccountQueueManager(cmd -> {
            if (cmd instanceof PostingCommand p) {
                stateMachine.applyPosting(p);
            }
        });
    }

    @AfterEach
    void tearDown() {
        queueManager.close();
    }

    @Test
    @DisplayName("Account Queue + Events: 20 concurrent requests produce 20 events with correct accountSeq")
    void concurrentRequests_produceCorrectEvents() throws Exception {
        int count = 20;
        CountDownLatch done = new CountDownLatch(count);

        for (int i = 0; i < count; i++) {
            final int idx = i;
            new Thread(() -> {
                PostingCommand cmd = new PostingCommand(
                        "int-req-" + idx, "RFQ", "RFQ-" + idx, LocalDate.now(),
                        List.of(new PostingCommand.Leg("leg-" + idx, "TRADE", List.of(
                                new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                        EntryType.DEBIT, BigDecimal.ONE, "Debit"),
                                new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD",
                                        EntryType.CREDIT, BigDecimal.ONE, "Credit")
                        )))
                );
                queueManager.submit("CLIENT_ACC_001", cmd);
                done.countDown();
            }).start();
        }

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(1000);

        // Verify events: 20 postings × 2 journalLines = 40 events
        assertThat(outboxEvents).hasSize(40);

        // Events should have sequential accountSeq
        List<BalanceChangeEvent> clientEvents = outboxEvents.stream()
                .filter(e -> "CLIENT_ACC_001".equals(e.accountId()))
                .toList();
        assertThat(clientEvents).hasSize(20);

        for (int i = 0; i < clientEvents.size() - 1; i++) {
            long seq = clientEvents.get(i).accountSeq();
            long nextSeq = clientEvents.get(i + 1).accountSeq();
            assertThat(nextSeq).as("accountSeq should be sequential")
                    .isEqualTo(seq + 1);
        }

        // Final balance
        AccountBalanceKey clientKey = new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD");
        assertThat(balanceStore.getOrThrow(clientKey).amount()).isEqualByComparingTo(new BigDecimal("9980.00"));
    }

    @Test
    @DisplayName("Hotspot account: 50 concurrent credits to COMPANY_FX_ACC all succeed")
    void hotspotAccount_concurrentCredits_allSucceed() throws Exception {
        int count = 50;
        CountDownLatch done = new CountDownLatch(count);

        // Create 50 client accounts
        for (int i = 0; i < count; i++) {
            String clientId = "CLIENT_HOT_" + i;
            accountMetaStore.put(clientId, new Account(
                    clientId, AccountType.CLIENT, "Client " + i,
                    "CUST-" + i, AccountStatus.ACTIVE, null, Instant.now()));
            balanceStore.put(new AccountBalanceKey(clientId, "AVAILABLE_BALANCE", "USD"),
                    new BalanceEntry(new BigDecimal("1000.00"), 0, 0, "", Instant.now()));
        }

        for (int i = 0; i < count; i++) {
            final int idx = i;
            final String clientId = "CLIENT_HOT_" + idx;
            new Thread(() -> {
                PostingCommand cmd = new PostingCommand(
                        "hot-req-" + idx, "RFQ", "RFQ-HOT-" + idx, LocalDate.now(),
                        List.of(new PostingCommand.Leg("leg-" + idx, "TRADE", List.of(
                                new PostingCommand.Line(clientId, "AVAILABLE_BALANCE", "USD",
                                        EntryType.DEBIT, new BigDecimal("10.00"), "Client pays"),
                                new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD",
                                        EntryType.CREDIT, new BigDecimal("10.00"), "Company receives")
                        )))
                );
                queueManager.submit("COMPANY_FX_ACC", cmd);
                done.countDown();
            }).start();
        }

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(2000);

        // Company balance = 100000 + 50 × 10 = 100500
        AccountBalanceKey companyKey = new AccountBalanceKey("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD");
        assertThat(balanceStore.getOrThrow(companyKey).amount()).isEqualByComparingTo(new BigDecimal("100500.00"));

        // Events: 50 postings × 2 lines = 100 events
        assertThat(outboxEvents).hasSize(100);
    }
}

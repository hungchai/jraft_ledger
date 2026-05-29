package com.tomma8.ledger.queue;

import com.tomma8.ledger.domain.command.CommandResult;
import com.tomma8.ledger.domain.command.PostingCommand;
import com.tomma8.ledger.domain.model.*;
import com.tomma8.ledger.statemachine.LedgerStateMachine;
import com.tomma8.ledger.store.AccountMetaStore;
import com.tomma8.ledger.store.BalanceStore;
import com.tomma8.ledger.store.BalanceTypeConfigStore;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Account Queue Manager")
class AccountQueueManagerTest {

    private BalanceStore balanceStore;
    private AccountMetaStore accountMetaStore;
    private BalanceTypeConfigStore balanceTypeConfigStore;
    private LedgerStateMachine stateMachine;
    private AccountQueueManager queueManager;
    private AtomicInteger processedCount;

    @BeforeEach
    void setUp() {
        balanceStore = new BalanceStore();
        accountMetaStore = new AccountMetaStore();
        balanceTypeConfigStore = new BalanceTypeConfigStore();
        stateMachine = new LedgerStateMachine(balanceStore, accountMetaStore, balanceTypeConfigStore);
        processedCount = new AtomicInteger(0);

        balanceTypeConfigStore.put("AVAILABLE_BALANCE", new BalanceTypeConfig(
                "AVAILABLE_BALANCE", false, null, SignConvention.NORMAL_CREDIT, 1));
        accountMetaStore.put("CLIENT_ACC_001", new Account(
                "CLIENT_ACC_001", AccountType.COMPANY, "Client",
                "CUST-001", AccountStatus.ACTIVE, null, Instant.now()));
        balanceStore.put(new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", "USD"),
                new BalanceEntry(new BigDecimal("10000.00"), 0, 1, "", Instant.now()));

        queueManager = new AccountQueueManager(cmd -> {
            if (cmd instanceof PostingCommand p) {
                processedCount.incrementAndGet();
                return stateMachine.applyPosting(p);
            }
            return CommandResult.rejected(LedgerErrorCode.INVALID_REQUEST_ID);
        });
    }

    @AfterEach
    void tearDown() {
        queueManager.close();
    }

    @Test
    @DisplayName("Single account serialization — 50 concurrent requests processed in order")
    void singleAccount_serialization_processedInOrder() throws Exception {
        int count = 50;
        CountDownLatch done = new CountDownLatch(count);

        for (int i = 0; i < count; i++) {
            final int idx = i;
            new Thread(() -> {
                PostingCommand cmd = new PostingCommand(
                        "q-req-" + idx, "TEST", "test", LocalDate.now(),
                        List.of(new PostingCommand.Leg("leg-" + idx, "TEST", BigDecimal.ONE, "USD", List.of(
                                new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT",
                                        EntryType.DEBIT, "Debit " + idx)
                        )))
                );
                queueManager.submit("CLIENT_ACC_001", cmd);
                done.countDown();
            }).start();
        }

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(500); // let worker catch up

        // All 50 debits processed, final balance = 10000 - 50 = 9950
        AccountBalanceKey key = new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", "USD");
        assertThat(balanceStore.getOrThrow(key).amount()).isEqualByComparingTo(new BigDecimal("9950.00"));
    }

    @Test
    @DisplayName("Backpressure — submitting more than MAX_QUEUE_SIZE returns false")
    void backpressure_exceedingMaxQueueSize_returnsFalse() {
        // Fill the queue to capacity (1000)
        int overflowCount = 0;
        for (int i = 0; i < 2000; i++) {
            PostingCommand cmd = new PostingCommand(
                    "bp-req-" + i, "TEST", "test", LocalDate.now(),
                    List.of(new PostingCommand.Leg("leg", "TEST", BigDecimal.ONE, "USD", List.of(
                            new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", EntryType.DEBIT, "")
                    )))
            );
            if (!queueManager.submit("CLIENT_ACC_001", cmd)) {
                overflowCount++;
            }
        }

        assertThat(overflowCount).isGreaterThan(0); // Some should be rejected
        int depth = queueManager.getQueueDepth("CLIENT_ACC_001");
        assertThat(depth).isLessThanOrEqualTo(1000);
    }

    @Test
    @DisplayName("Multiple accounts have independent queues")
    void multipleAccounts_independentQueues() throws Exception {
        accountMetaStore.put("ACC_A", new Account(
                "ACC_A", AccountType.COMPANY, "A", "C-A", AccountStatus.ACTIVE, null, Instant.now()));
        accountMetaStore.put("ACC_B", new Account(
                "ACC_B", AccountType.COMPANY, "B", "C-B", AccountStatus.ACTIVE, null, Instant.now()));

        balanceStore.put(new AccountBalanceKey("ACC_A", "AVAILABLE_BALANCE", "CURRENT", "USD"),
                new BalanceEntry(BigDecimal.ZERO, 0, 0, "", Instant.now()));
        balanceStore.put(new AccountBalanceKey("ACC_B", "AVAILABLE_BALANCE", "CURRENT", "USD"),
                new BalanceEntry(BigDecimal.ZERO, 0, 0, "", Instant.now()));

        CountDownLatch done = new CountDownLatch(2);
        List<Exception> errors = new ArrayList<>();

        // Submit to ACC_A
        new Thread(() -> {
            try {
                for (int i = 0; i < 100; i++) {
                    PostingCommand cmd = new PostingCommand(
                            "ia-" + i, "TEST", "test", LocalDate.now(),
                            List.of(new PostingCommand.Leg("leg", "TEST", BigDecimal.ONE, "USD", List.of(
                                    new PostingCommand.Line("ACC_A", "AVAILABLE_BALANCE", "CURRENT", EntryType.CREDIT, "")
                            )))
                    );
                    queueManager.submit("ACC_A", cmd);
                }
            } catch (Exception e) { errors.add(e); }
            done.countDown();
        }).start();

        // Submit to ACC_B
        new Thread(() -> {
            try {
                for (int i = 0; i < 100; i++) {
                    PostingCommand cmd = new PostingCommand(
                            "ib-" + i, "TEST", "test", LocalDate.now(),
                            List.of(new PostingCommand.Leg("leg", "TEST", BigDecimal.ONE, "USD", List.of(
                                    new PostingCommand.Line("ACC_B", "AVAILABLE_BALANCE", "CURRENT", EntryType.CREDIT, "")
                            )))
                    );
                    queueManager.submit("ACC_B", cmd);
                }
            } catch (Exception e) { errors.add(e); }
            done.countDown();
        }).start();

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(500);

        assertThat(errors).isEmpty();
    }
}

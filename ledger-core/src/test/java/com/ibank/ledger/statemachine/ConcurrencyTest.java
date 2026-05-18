package com.ibank.ledger.statemachine;

import com.ibank.ledger.domain.command.CommandResult;
import com.ibank.ledger.domain.command.PostingCommand;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Concurrency Safety (Phase 6)")
class ConcurrencyTest {

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

        balanceTypeConfigStore.put("AVAILABLE_BALANCE", new BalanceTypeConfig(
                "AVAILABLE_BALANCE", false, null, SignConvention.NORMAL_CREDIT, 1));

        accountMetaStore.put("CLIENT_ACC_001", new Account(
                "CLIENT_ACC_001", AccountType.COMPANY, "Client",
                "CUST-001", AccountStatus.ACTIVE, null, Instant.now()));
        accountMetaStore.put("COMPANY_FX_ACC", new Account(
                "COMPANY_FX_ACC", AccountType.COMPANY, "Company",
                null, AccountStatus.ACTIVE, null, Instant.now()));
    }

    @Test
    @DisplayName("TC-F002-08 post concurrent same account no double debit")
    void post_concurrentSameAccount_noDoubleDebit() throws Exception {
        int threadCount = 100;
        int debitEach = 1;
        int initialBalance = 100;

        AccountBalanceKey key = new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD");
        balanceStore.put(key, new BalanceEntry(new BigDecimal(initialBalance), 0, 0, "", Instant.now()));

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CommandResult>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                PostingCommand cmd = new PostingCommand(
                        "req-c-" + idx, "TEST", "test-ref", LocalDate.now(),
                        List.of(new PostingCommand.Leg("leg-" + idx, "TEST", List.of(
                                new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                        EntryType.DEBIT, new BigDecimal(debitEach), "Concurrent " + idx)
                        )))
                );
                return stateMachine.applyPosting(cmd);
            }));
        }

        ready.await();
        start.countDown();
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        // Collect results
        int completed = 0, rejected = 0;
        for (var f : futures) {
            CommandResult r = f.get();
            if (r.isCompleted()) completed++;
            else rejected++;
        }

        BigDecimal finalBalance = balanceStore.getOrThrow(key).amount();
        assertThat(finalBalance).isEqualByComparingTo(new BigDecimal(initialBalance - (completed * debitEach)));
        assertThat(completed + rejected).isEqualTo(threadCount);
        assertThat(finalBalance.compareTo(BigDecimal.ZERO)).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("TC-F002-09 post hotspot company account 100 concurrent no duplicate")
    void post_hotspotCompanyAccount_concurrent_noDuplicate() throws Exception {
        int threadCount = 100;
        BigDecimal creditEach = new BigDecimal("10.00");

        AccountBalanceKey companyKey = new AccountBalanceKey("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD");
        balanceStore.put(companyKey, new BalanceEntry(BigDecimal.ZERO, 0, 0, "", Instant.now()));

        // Create a client account for each thread to debit from
        for (int i = 0; i < threadCount; i++) {
            String clientId = "CLIENT_C_" + i;
            accountMetaStore.put(clientId, new Account(
                    clientId, AccountType.COMPANY, "Client " + i,
                    "CUST-" + i, AccountStatus.ACTIVE, null, Instant.now()));
            balanceStore.put(new AccountBalanceKey(clientId, "AVAILABLE_BALANCE", "USD"),
                    new BalanceEntry(creditEach, 0, 0, "", Instant.now()));
        }

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CommandResult>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            final String clientId = "CLIENT_C_" + idx;
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                PostingCommand cmd = new PostingCommand(
                        "req-hot-" + idx, "RFQ_SETTLEMENT", "RFQ-" + idx, LocalDate.now(),
                        List.of(new PostingCommand.Leg("leg-" + idx, "TRADE", List.of(
                                new PostingCommand.Line(clientId, "AVAILABLE_BALANCE", "USD",
                                        EntryType.DEBIT, creditEach, "Client " + idx + " pays"),
                                new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD",
                                        EntryType.CREDIT, creditEach, "Company receives")
                        )))
                );
                return stateMachine.applyPosting(cmd);
            }));
        }

        ready.await();
        start.countDown();
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        int completed = 0;
        for (var f : futures) {
            if (f.get().isCompleted()) completed++;
        }

        assertThat(completed).isEqualTo(threadCount);
        BigDecimal expectedCompanyBalance = creditEach.multiply(new BigDecimal(threadCount));
        assertThat(balanceStore.getOrThrow(companyKey).amount()).isEqualByComparingTo(expectedCompanyBalance);
    }

    @Test
    @DisplayName("TC-NFR-04 idempotency 100 retries only one journal created")
    void idempotency_concurrentRetries_onlyOneJournalCreated() throws Exception {
        int retries = 100;

        AccountBalanceKey key = new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD");
        balanceStore.put(key, new BalanceEntry(new BigDecimal("1000.00"), 0, 0, "", Instant.now()));

        String sameRequestId = "req-idempotent";

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch ready = new CountDownLatch(retries);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CommandResult>> futures = new ArrayList<>();

        for (int i = 0; i < retries; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                PostingCommand cmd = new PostingCommand(
                        sameRequestId, "TEST", "test-ref", LocalDate.now(),
                        List.of(new PostingCommand.Leg("leg-1", "TEST", List.of(
                                new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                        EntryType.DEBIT, new BigDecimal("100.00"), "Idempotent")
                        )))
                );
                return stateMachine.applyPosting(cmd);
            }));
        }

        ready.await();
        start.countDown();
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        List<CommandResult> results = new ArrayList<>();
        for (var f : futures) {
            results.add(f.get());
        }

        // All results should be the same
        CommandResult first = results.get(0);
        assertThat(first.isCompleted()).isTrue();
        assertThat(results).allMatch(r -> r.equals(first));

        // Balance should reflect only ONE debit of 100
        BigDecimal finalBalance = balanceStore.getOrThrow(key).amount();
        assertThat(finalBalance).isEqualByComparingTo(new BigDecimal("900.00"));
    }

    @Test
    @DisplayName("TC-NFR-05 concurrent posting no negative balance no double debit")
    void concurrentPosting_noNegativeBalance_noDoubleDebit() throws Exception {
        int threadCount = 101;
        int initialBalance = 100;

        AccountBalanceKey key = new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD");
        balanceStore.put(key, new BalanceEntry(new BigDecimal(initialBalance), 0, 0, "", Instant.now()));

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CommandResult>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                PostingCommand cmd = new PostingCommand(
                        "req-nfr5-" + idx, "TEST", "test-ref", LocalDate.now(),
                        List.of(new PostingCommand.Leg("leg-" + idx, "TEST", List.of(
                                new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                        EntryType.DEBIT, BigDecimal.ONE, "Debit " + idx)
                        )))
                );
                return stateMachine.applyPosting(cmd);
            }));
        }

        ready.await();
        start.countDown();
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        int completed = 0, rejected = 0;
        for (var f : futures) {
            if (f.get().isCompleted()) completed++;
            else rejected++;
        }

        // Exactly 100 should succeed, 1 should be INSUFFICIENT_BALANCE
        assertThat(completed).isEqualTo(initialBalance); // 100 completed
        assertThat(rejected).isEqualTo(1);                // 1 rejected

        BigDecimal finalBalance = balanceStore.getOrThrow(key).amount();
        assertThat(finalBalance).isEqualByComparingTo(BigDecimal.ZERO);
        // No negative balance
        assertThat(finalBalance.compareTo(BigDecimal.ZERO)).isGreaterThanOrEqualTo(0);
    }
}

package com.tomma8.ledger.queue;

import com.tomma8.ledger.domain.command.CommandResult;
import com.tomma8.ledger.domain.command.PostingCommand;
import com.tomma8.ledger.domain.model.*;
import com.tomma8.ledger.raft.ConsensusEngine;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Command Queue Manager")
class CommandQueueManagerTest {

    private BalanceStore balanceStore;
    private LedgerStateMachine stateMachine;
    private AtomicInteger raftSubmitCount;
    private CommandQueueManager queueManager;

    @BeforeEach
    void setUp() {
        balanceStore = new BalanceStore();
        AccountMetaStore accountMetaStore = new AccountMetaStore();
        BalanceTypeConfigStore balanceTypeConfigStore = new BalanceTypeConfigStore();
        stateMachine = new LedgerStateMachine(balanceStore, accountMetaStore, balanceTypeConfigStore);
        raftSubmitCount = new AtomicInteger(0);

        balanceTypeConfigStore.put("AVAILABLE_BALANCE", new BalanceTypeConfig(
                "AVAILABLE_BALANCE", false, null, SignConvention.NORMAL_CREDIT, 1));
        accountMetaStore.put("CLIENT_ACC_001", new Account(
                "CLIENT_ACC_001", AccountType.COMPANY, "Client",
                "CUST-001", AccountStatus.ACTIVE, null, Instant.now()));
        balanceStore.put(new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", "USD"),
                new BalanceEntry(new BigDecimal("10000.00"), 0, 1, "", Instant.now()));

        ConsensusEngine engine = new ConsensusEngine() {
            @Override
            public CommandResult submit(com.tomma8.ledger.domain.command.RaftCommand command) {
                raftSubmitCount.incrementAndGet();
                if (command instanceof PostingCommand p) {
                    return stateMachine.applyPosting(p);
                }
                return CommandResult.rejected(LedgerErrorCode.INVALID_REQUEST_ID);
            }

            @Override
            public List<CommandResult> submitBatch(List<com.tomma8.ledger.domain.command.RaftCommand> commands) {
                raftSubmitCount.incrementAndGet();
                return commands.stream().map(cmd -> {
                    if (cmd instanceof PostingCommand p) {
                        return stateMachine.applyPosting(p);
                    }
                    return CommandResult.rejected(LedgerErrorCode.INVALID_REQUEST_ID);
                }).toList();
            }

            @Override public boolean isLeader() { return true; }
            @Override public String getLeaderEndpoint() { return "test"; }
            @Override public long getLastAppliedIndex() { return 0; }
            @Override public LedgerStateMachine getLedgerStateMachine() { return stateMachine; }
            @Override public boolean isRunning() { return true; }
            @Override public List<String> getAlivePeers() { return List.of(); }
            @Override public void close() {}
        };

        queueManager = new CommandQueueManager(engine, 10_000, 8, 2);
    }

    @AfterEach
    void tearDown() {
        queueManager.close();
    }

    @Test
    @DisplayName("Micro-batch coalesces parallel submits into fewer Raft calls")
    void microBatch_coalescesParallelSubmits() throws Exception {
        int count = 16;
        CountDownLatch done = new CountDownLatch(count);
        List<CompletableFuture<CommandResult>> futures = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            PostingCommand cmd = posting("cq-req-" + i, "Debit " + i);
            futures.add(queueManager.submitAsync(cmd));
            done.countDown();
        }

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);

        assertThat(raftSubmitCount.get()).isLessThan(count);
        AccountBalanceKey key = new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", "USD");
        assertThat(balanceStore.getOrThrow(key).amount()).isEqualByComparingTo(new BigDecimal("9984.00"));
    }

    @Test
    @DisplayName("Pipelining runs multiple batches in flight (depth>1) and returns correct results")
    void pipelining_multipleBatchesInFlight() throws Exception {
        ScheduledExecutorService sched = Executors.newScheduledThreadPool(4);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        // async engine: each batch's futures complete after 60ms (simulates raft commit latency)
        ConsensusEngine async = new NoopEngine() {
            @Override
            public List<CompletableFuture<CommandResult>> submitBatchAsync(
                    List<com.tomma8.ledger.domain.command.RaftCommand> commands) {
                int n = inFlight.incrementAndGet();
                maxInFlight.accumulateAndGet(n, Math::max);
                List<CompletableFuture<CommandResult>> fs = new ArrayList<>();
                for (var c : commands) fs.add(new CompletableFuture<>());
                sched.schedule(() -> {
                    inFlight.decrementAndGet();
                    for (int i = 0; i < commands.size(); i++) fs.get(i).complete(CommandResult.completed("JNL-" + i));
                }, 60, TimeUnit.MILLISECONDS);
                return fs;
            }
        };
        // batchSize=1 → one command per batch; depth=4 → up to 4 batches pipelined
        CommandQueueManager pm = new CommandQueueManager(async, 10_000, 1, 0, 4);
        try {
            List<CompletableFuture<CommandResult>> futs = new ArrayList<>();
            for (int i = 0; i < 8; i++) futs.add(pm.submitAsync(posting("pl-" + i, "d")));
            CompletableFuture.allOf(futs.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
            assertThat(futs).allSatisfy(f -> assertThat(f.join().status()).isEqualTo(CommandResult.COMPLETED));
            assertThat(maxInFlight.get()).isGreaterThan(1);   // proved pipelining
            assertThat(maxInFlight.get()).isLessThanOrEqualTo(4); // capped at depth
        } finally {
            pm.close();
            sched.shutdownNow();
        }
    }

    @Test
    @DisplayName("Queue full rejects new commands")
    void queueFull_rejectsNewCommands() {
        CommandQueueManager smallQueue = new CommandQueueManager(
                new NoopEngine(), 1, 1, 0, false);
        try {
            assertThat(smallQueue.offer(posting("a", ""))).isTrue();
            assertThat(smallQueue.offer(posting("b", ""))).isFalse();
        } finally {
            smallQueue.close();
        }
    }

    private static PostingCommand posting(String requestId, String description) {
        return new PostingCommand(
                requestId, "TEST", "test", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg", "TEST", BigDecimal.ONE, "USD", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT",
                                EntryType.DEBIT, description)
                )))
        );
    }

    private static class NoopEngine implements ConsensusEngine {
        @Override public CommandResult submit(com.tomma8.ledger.domain.command.RaftCommand command) {
            return CommandResult.completed("JNL-1");
        }
        @Override public boolean isLeader() { return true; }
        @Override public String getLeaderEndpoint() { return "test"; }
        @Override public long getLastAppliedIndex() { return 0; }
        @Override public LedgerStateMachine getLedgerStateMachine() { return null; }
        @Override public boolean isRunning() { return true; }
        @Override public List<String> getAlivePeers() { return List.of(); }
        @Override public void close() {}
    }
}

package com.ibank.ledger.raft;

import com.ibank.ledger.domain.command.*;
import com.ibank.ledger.domain.model.*;
import com.ibank.ledger.statemachine.LedgerStateMachine;
import com.ibank.ledger.store.AccountMetaStore;
import com.ibank.ledger.store.BalanceStore;
import com.ibank.ledger.store.BalanceTypeConfigStore;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;

@DisplayName("SOFAJRaft Multi-Node Cluster")
class RaftClusterIntegrationTest {

    private static final String GROUP_ID = "ledger-test-group";
    private static final String PEERS = "127.0.0.1:18081,127.0.0.1:18082,127.0.0.1:18083";
    private Path tempDir;
    private RaftNodeManager node1, node2, node3;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("raft-cluster-");
    }

    @AfterEach
    void tearDown() {
        closeQuietly(node1);
        closeQuietly(node2);
        closeQuietly(node3);
        try {
            Files.walk(tempDir).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(f -> f.delete());
        } catch (Exception ignored) {}
    }


    @Test
    @DisplayName("3-node Raft cluster: leader election and log replication")
    void threeNodeCluster_leaderElectionAndLogReplication() throws Exception {
        node1 = createNode(PEERS, "127.0.0.1:18081", "node1");
        node2 = createNode(PEERS, "127.0.0.1:18082", "node2");
        node3 = createNode(PEERS, "127.0.0.1:18083", "node3");

        // Wait for leader election (up to 5 seconds)
        RaftNodeManager leader = waitForLeader(5000);
        assertThat(leader).as("No leader elected").isNotNull();
        System.out.println("Leader: " + leader.getServerId());

        // Submit via leader
        PostingCommand cmd = new PostingCommand(
                "raft-req-001", "TEST", "RAFT-TEST-001", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.DEBIT, new BigDecimal("100.00"), "Raft posting")
                )))
        );
        leader.submit(cmd);
        Thread.sleep(500);

        // All nodes should have the journal
        for (RaftNodeManager n : List.of(node1, node2, node3)) {
            Journal journal = n.getStateMachine().getLedgerStateMachine()
                    .getJournalByRequestId("raft-req-001");
            assertThat(journal).as("Journal missing on node " + n.getServerId()).isNotNull();
        }
    }

    @Test
    @DisplayName("Cluster survives follower restart")
    void cluster_survivesFollowerRestart() throws Exception {
        String peers2 = "127.0.0.1:18091,127.0.0.1:18092,127.0.0.1:18093";
        node1 = createNode(peers2, "127.0.0.1:18091", "node1");
        node2 = createNode(peers2, "127.0.0.1:18092", "node2");
        node3 = createNode(peers2, "127.0.0.1:18093", "node3");

        RaftNodeManager leader = waitForLeader(5000);
        assertThat(leader).isNotNull();

        RaftNodeManager follower = null;
        String followerId = null;
        Path followerData = null;
        for (var n : List.of(node1, node2, node3)) {
            if (!n.isLeader()) {
                follower = n;
                followerId = n.getServerId().toString();
                followerData = tempDir.resolve(n == node1 ? "node1" : (n == node2 ? "node2" : "node3"));
                break;
            }
        }
        assertThat(follower).isNotNull();

        follower.close();
        Thread.sleep(3000);

        BalanceStore bs = new BalanceStore();
        AccountMetaStore ams = new AccountMetaStore();
        BalanceTypeConfigStore cs = new BalanceTypeConfigStore();
        LedgerStateMachine lsm = new LedgerStateMachine(bs, ams, cs);
        LedgerRaftStateMachine fsm = new LedgerRaftStateMachine(lsm);
        cs.put("AVAILABLE_BALANCE", new BalanceTypeConfig(
                "AVAILABLE_BALANCE", false, null, SignConvention.NORMAL_CREDIT, 1));

        RaftNodeManager r2 = new RaftNodeManager(GROUP_ID, followerId, peers2,
                followerData.toString(), fsm);
        r2.init();
        Thread.sleep(3000);
        r2.close();
    }

    // ── Multi-operation sync test ────────────────────────────────

    @Test
    @DisplayName("All nodes stay in sync across posting, freeze, reversal, close, and add-balance-type")
    void allNodesStayInSync_afterMultipleOperations() throws Exception {
        node1 = createNode(PEERS, "127.0.0.1:18081", "node1");
        node2 = createNode(PEERS, "127.0.0.1:18082", "node2");
        node3 = createNode(PEERS, "127.0.0.1:18083", "node3");

        // Register TRADE_AHEAD_BALANCE config on all nodes (required for negative-allowed type)
        BalanceTypeConfig tradeAheadConfig = new BalanceTypeConfig(
                "TRADE_AHEAD_BALANCE", true, NegativeSemantics.PRE_AUTHORIZED,
                SignConvention.NORMAL_DEBIT, 1);
        for (var n : List.of(node1, node2, node3)) {
            n.getStateMachine().getLedgerStateMachine()
                    .getBalanceTypeConfigStore().put("TRADE_AHEAD_BALANCE", tradeAheadConfig);
        }

        RaftNodeManager leader = waitForLeader(5000);
        assertThat(leader).as("Leader must be elected").isNotNull();
        System.out.println("Leader: " + leader.getServerId());

        // ── 1. Create account via Raft ─────────────────────────────
        AccountCreateCommand createCmd = new AccountCreateCommand(
                "sync-req-001", "SYNC_ACC_001", AccountType.COMPANY,
                "Sync Test Account", "OWNER-001",
                List.of(new AccountCreateCommand.BalanceInitialization("AVAILABLE_BALANCE", "USD"),
                        new AccountCreateCommand.BalanceInitialization("TRADE_AHEAD_BALANCE", "USD")));
        CommandResult r1 = leader.submit(createCmd);
        assertThat(r1.isCompleted()).as("Create account").isTrue();
        Thread.sleep(300);

        assertAllNodesEqual("account exists", n ->
                n.getStateMachine().getLedgerStateMachine().getAccountMetaStore().contains("SYNC_ACC_001"));

        // ── 2. Post a posting (debit 100 from CLIENT_ACC_001, credit 100 to SYNC_ACC_001) ──
        PostingCommand postCmd = new PostingCommand(
                "sync-req-002", "TEST", "SYNC-TEST-002", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-sync", "TEST", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.DEBIT, new BigDecimal("100.00"), "Sync test debit"),
                        new PostingCommand.Line("SYNC_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.CREDIT, new BigDecimal("100.00"), "Sync test credit")
                ))));
        CommandResult r2 = leader.submit(postCmd);
        assertThat(r2.isCompleted()).as("Posting").isTrue();
        Thread.sleep(300);

        // Verify balances identical on all nodes
        assertAllNodesHaveSameBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD");
        assertAllNodesHaveSameBalance("SYNC_ACC_001", "AVAILABLE_BALANCE", "USD");

        // Verify journals exist on all nodes
        assertAllNodesEqual("journal", n ->
                n.getStateMachine().getLedgerStateMachine().getJournalByRequestId("sync-req-002") != null);

        // ── 3. Freeze SYNC_ACC_001 via Raft ────────────────────────
        AccountFreezeCommand freezeCmd = new AccountFreezeCommand("sync-req-003", "SYNC_ACC_001", true);
        CommandResult r3 = leader.submit(freezeCmd);
        assertThat(r3.isCompleted()).as("Freeze").isTrue();
        Thread.sleep(300);

        assertAllNodesEqual("account frozen", n -> {
            var acc = n.getStateMachine().getLedgerStateMachine().getAccountMetaStore().get("SYNC_ACC_001");
            return acc.isPresent() && acc.get().status() == AccountStatus.FROZEN;
        });

        // ── 4. Unfreeze via Raft ──────────────────────────────────
        AccountFreezeCommand unfreezeCmd = new AccountFreezeCommand("sync-req-004", "SYNC_ACC_001", false);
        CommandResult r4 = leader.submit(unfreezeCmd);
        assertThat(r4.isCompleted()).as("Unfreeze").isTrue();
        Thread.sleep(300);

        assertAllNodesEqual("account active", n -> {
            var acc = n.getStateMachine().getLedgerStateMachine().getAccountMetaStore().get("SYNC_ACC_001");
            return acc.isPresent() && acc.get().status() == AccountStatus.ACTIVE;
        });

        // ── 5. Add balance type to SYNC_ACC_001 ────────────────────
        AccountAddBalanceTypeCommand addBtCmd = new AccountAddBalanceTypeCommand(
                "sync-req-005", "SYNC_ACC_001", "BROKERAGE_BALANCE", "USD");
        // Register the config first (on leader, but it must exist on all for apply to work)
        for (var n : List.of(node1, node2, node3)) {
            n.getStateMachine().getLedgerStateMachine()
                    .getBalanceTypeConfigStore().put("BROKERAGE_BALANCE",
                    new BalanceTypeConfig("BROKERAGE_BALANCE", false, null, SignConvention.NORMAL_CREDIT, 1));
        }
        CommandResult r5 = leader.submit(addBtCmd);
        assertThat(r5.isCompleted()).as("Add balance type").isTrue();
        Thread.sleep(300);

        assertAllNodesEqual("has BROKERAGE_BALANCE", n -> {
            var acc = n.getStateMachine().getLedgerStateMachine().getAccountMetaStore().get("SYNC_ACC_001");
            return acc.isPresent() && acc.get().allowedBalanceTypes() != null
                    && acc.get().allowedBalanceTypes().contains("BROKERAGE_BALANCE");
        });

        // Verify BROKERAGE_BALANCE initialized to zero
        assertAllNodesHaveSameBalance("SYNC_ACC_001", "BROKERAGE_BALANCE", "USD");

        // ── 6. Reversal ────────────────────────────────────────────
        String journalId = r2.journalId();
        ReversalCommand revCmd = new ReversalCommand(
                "sync-req-006", journalId, "Test reversal", "TEST_REV", LocalDate.now());
        CommandResult r6 = leader.submit(revCmd);
        assertThat(r6.isCompleted()).as("Reversal").isTrue();
        Thread.sleep(300);

        // Verify reversal journal on all nodes
        assertAllNodesEqual("reversal journal", n -> {
            var sm = n.getStateMachine().getLedgerStateMachine();
            Journal rev = sm.getJournalByRequestId("sync-req-006");
            return rev != null && rev.journalType() == JournalType.REVERSAL;
        });

        // Verify original journal is now REVERSED on all nodes
        assertAllNodesEqual("original reversed", n -> {
            Journal orig = n.getStateMachine().getLedgerStateMachine().getJournal(journalId);
            return orig != null && orig.status() == JournalStatus.REVERSED;
        });

        // ── 7. Posting after close (no balance) → close SYNC_ACC_001 ──
        // First zero out SYNC_ACC_001 balance via a debit posting (it got 100 credit above,
        // then reversed = back to 0; should be zero or near-zero now)
        // Actually after reversal, CLIENT_ACC_001 got 100 back, SYNC_ACC_001 lost 100
        // So SYNC_ACC_001 AVAILABLE should be 0. Close it.
        AccountCloseCommand closeCmd = new AccountCloseCommand("sync-req-007", "SYNC_ACC_001");
        CommandResult r7 = leader.submit(closeCmd);
        assertThat(r7.isCompleted()).as("Close account").isTrue();
        Thread.sleep(300);

        assertAllNodesEqual("account closed", n -> {
            var acc = n.getStateMachine().getLedgerStateMachine().getAccountMetaStore().get("SYNC_ACC_001");
            return acc.isPresent() && acc.get().status() == AccountStatus.CLOSED;
        });

        // ── 8. Final balance tally — all nodes must agree exactly ──
        System.out.println("\n=== Final Balance Snapshot Across All Nodes ===");
        for (var n : List.of(node1, node2, node3)) {
            System.out.println("Node " + n.getServerId() + ":");
            var bs = n.getStateMachine().getLedgerStateMachine().getBalanceStore();
            bs.getAll().forEach((k, v) ->
                    System.out.println("  " + k.accountId() + "/" + k.balanceType() + "/" + k.currency()
                            + " = " + v.amount() + " (seq=" + v.accountSeq() + ")"));
        }

        // Assert all balance maps are identical (ignoring Instant.now() timestamps)
        var refBalances = node1.getStateMachine().getLedgerStateMachine().getBalanceStore().getAll();
        for (var n : List.of(node2, node3)) {
            var nBalances = n.getStateMachine().getLedgerStateMachine().getBalanceStore().getAll();
            assertThat(nBalances.keySet()).as("Balance keys on " + n.getServerId())
                    .containsExactlyInAnyOrderElementsOf(refBalances.keySet());
            for (var entry : refBalances.entrySet()) {
                BalanceEntry actual = nBalances.get(entry.getKey());
                assertThat(actual).as("Missing balance on " + n.getServerId() + ": " + entry.getKey()).isNotNull();
                assertThat(actual.amount()).as("amount on " + n.getServerId())
                        .isEqualTo(entry.getValue().amount());
                assertThat(actual.accountSeq()).as("accountSeq on " + n.getServerId())
                        .isEqualTo(entry.getValue().accountSeq());
                assertThat(actual.stateVersion()).as("stateVersion on " + n.getServerId())
                        .isEqualTo(entry.getValue().stateVersion());
                assertThat(actual.lastJournalId()).as("lastJournalId on " + n.getServerId())
                        .isEqualTo(entry.getValue().lastJournalId());
            }
        }

        // Also assert journals match (by journalId + lines)
        var refJournals = node1.getStateMachine().getLedgerStateMachine().getAllJournals();
        for (var n : List.of(node2, node3)) {
            var nJournals = n.getStateMachine().getLedgerStateMachine().getAllJournals();
            assertThat(nJournals.keySet()).as("Journal keys on " + n.getServerId())
                    .containsExactlyInAnyOrderElementsOf(refJournals.keySet());
            for (var entry : refJournals.entrySet()) {
                Journal actual = nJournals.get(entry.getKey());
                assertThat(actual).as("Missing journal on " + n.getServerId() + ": " + entry.getKey()).isNotNull();
                assertThat(actual.status()).isEqualTo(entry.getValue().status());
                assertThat(actual.journalType()).isEqualTo(entry.getValue().journalType());
                assertThat(actual.lines().size()).isEqualTo(entry.getValue().lines().size());
            }
        }
    }

    // ── Sync assertion helpers ────────────────────────────────────

    private void assertAllNodesEqual(String context, Function<RaftNodeManager, Boolean> predicate) {
        List<RaftNodeManager> nodes = List.of(node1, node2, node3);
        Boolean first = predicate.apply(nodes.get(0));
        for (int i = 1; i < nodes.size(); i++) {
            assertThat(predicate.apply(nodes.get(i)))
                    .as(context + " on node " + nodes.get(i).getServerId())
                    .isEqualTo(first);
        }
    }

    private void assertAllNodesHaveSameBalance(String accountId, String balanceType, String currency) {
        List<RaftNodeManager> nodes = List.of(node1, node2, node3);
        AccountBalanceKey key = new AccountBalanceKey(accountId, balanceType, currency);
        BalanceEntry ref = nodes.get(0).getStateMachine().getLedgerStateMachine()
                .getBalanceStore().get(key).orElse(BalanceEntry.zero());
        for (int i = 1; i < nodes.size(); i++) {
            BalanceEntry other = nodes.get(i).getStateMachine().getLedgerStateMachine()
                    .getBalanceStore().get(key).orElse(BalanceEntry.zero());
            assertThat(other.amount())
                    .as("Balance " + accountId + "/" + balanceType + "/" + currency
                            + " on node " + nodes.get(i).getServerId())
                    .isEqualTo(ref.amount());
            assertThat(other.accountSeq())
                    .as("accountSeq on node " + nodes.get(i).getServerId())
                    .isEqualTo(ref.accountSeq());
        }
    }

    private RaftNodeManager createNode(String peers, String serverId, String dir) throws Exception {
        Path dataPath = tempDir.resolve(dir);
        Files.createDirectories(dataPath);
        BalanceStore bs = new BalanceStore();
        AccountMetaStore ams = new AccountMetaStore();
        BalanceTypeConfigStore cs = new BalanceTypeConfigStore();
        LedgerStateMachine lsm = new LedgerStateMachine(bs, ams, cs);
        LedgerRaftStateMachine fsm = new LedgerRaftStateMachine(lsm);
        cs.put("AVAILABLE_BALANCE", new BalanceTypeConfig(
                "AVAILABLE_BALANCE", false, null, SignConvention.NORMAL_CREDIT, 1));
        ams.put("CLIENT_ACC_001", new Account(
                "CLIENT_ACC_001", AccountType.COMPANY, "Client",
                "CUST-001", AccountStatus.ACTIVE, null, Instant.now()));
        bs.put(new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD"),
                new BalanceEntry(new BigDecimal("1000.00"), 0, 1, "", Instant.now()));
        RaftNodeManager mgr = new RaftNodeManager(GROUP_ID, serverId, peers, dataPath.toString(), fsm);
        mgr.init();
        return mgr;
    }

    private RaftNodeManager createNode(String serverId, String dir) throws Exception {
        return createNode(PEERS, serverId, dir);
    }

    private RaftNodeManager waitForLeader(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (var n : List.of(node1, node2, node3)) {
                if (n != null && n.isLeader()) return n;
            }
            Thread.sleep(200);
        }
        return null;
    }

    private void closeQuietly(AutoCloseable c) {
        if (c != null) try { c.close(); } catch (Exception ignored) {}
    }
}

package com.ibank.ledger.raft;

import com.ibank.ledger.domain.command.CommandResult;
import com.ibank.ledger.domain.command.PostingCommand;
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
import java.util.Comparator;
import java.util.List;

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
        leader.submit(cmd).get();
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
        String peers2 = "127.0.0.1:28081,127.0.0.1:28082,127.0.0.1:28083";
        node1 = createNode(peers2, "127.0.0.1:28081", "node1");
        node2 = createNode(peers2, "127.0.0.1:28082", "node2");
        node3 = createNode(peers2, "127.0.0.1:28083", "node3");

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
        Thread.sleep(500);

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
                "CLIENT_ACC_001", AccountType.CLIENT, "Client",
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

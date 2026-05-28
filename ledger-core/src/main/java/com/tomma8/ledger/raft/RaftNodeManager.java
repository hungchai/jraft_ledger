package com.tomma8.ledger.raft;

import com.alipay.sofa.jraft.Node;
import com.alipay.sofa.jraft.RaftGroupService;
import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.conf.Configuration;
import com.alipay.sofa.jraft.entity.PeerId;
import com.alipay.sofa.jraft.entity.Task;
import com.alipay.sofa.jraft.option.NodeOptions;
import com.alipay.sofa.jraft.option.RaftOptions;
import com.tomma8.ledger.domain.command.CommandResult;
import com.tomma8.ledger.domain.command.RaftCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class RaftNodeManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RaftNodeManager.class);

    private final String groupId;
    private final PeerId serverId;
    private final NodeOptions nodeOptions;
    private final LedgerRaftStateMachine stateMachine;

    private RaftGroupService raftGroupService;
    private Node node;

    // Map of requestId → future, populated by submit(), completed by onApply()
    final ConcurrentHashMap<String, CompletableFuture<CommandResult>> pendingCommands = new ConcurrentHashMap<>();

    public RaftNodeManager(String groupId, String serverIdStr, String peerList,
                            String dataPath, LedgerRaftStateMachine stateMachine) {
        this.groupId = groupId;
        this.serverId = new PeerId();
        this.serverId.parse(serverIdStr);
        Configuration conf = new Configuration();
        conf.parse(peerList);
        this.stateMachine = stateMachine;
        stateMachine.setPendingCommands(pendingCommands);

        this.nodeOptions = new NodeOptions();
        nodeOptions.setElectionTimeoutMs(1000);
        nodeOptions.setInitialConf(conf);
        nodeOptions.setDisableCli(false);
        nodeOptions.setSnapshotIntervalSecs(3600);
        nodeOptions.setLogUri(dataPath + File.separator + "log");
        nodeOptions.setRaftMetaUri(dataPath + File.separator + "raft_meta");
        nodeOptions.setSnapshotUri(dataPath + File.separator + "snapshot");
        nodeOptions.setFsm(this.stateMachine);

        // Tune Raft pipelining for higher throughput. Defaults (256 inflight)
        // are conservative; the leader can keep more append entries in flight
        // to fully use the network path between nodes. Sync flag controls
        // whether log entries fsync before being considered appended; with
        // sync=false we trade a small durability window for ~2× throughput.
        // Keep disruptor buffer at default — resizing it at startup broke the
        // FSM leader callback dispatch in earlier experiments.
        RaftOptions ro = nodeOptions.getRaftOptions();
        if (ro == null) {
            ro = new RaftOptions();
            nodeOptions.setRaftOptions(ro);
        }
        ro.setMaxReplicatorInflightMsgs(
                Integer.parseInt(System.getenv().getOrDefault("RAFT_MAX_INFLIGHT", "1024")));
        // applyBatch=128 added ~7-8ms of disruptor-wait latency at low load
        // on macOS (BlockingWaitStrategy wakeup is several ms). 32 is the
        // SOFAJRaft default and gives sub-ms p50 under light traffic without
        // hurting high-rps throughput.
        ro.setApplyBatch(
                Integer.parseInt(System.getenv().getOrDefault("RAFT_APPLY_BATCH", "32")));
        // sync=false: don't fsync Raft log per batch on the leader. On macOS
        // F_FULLFSYNC is ~10–20 ms and dominates write p99. Durability comes
        // from quorum replication across the 3-node group, so per-leader
        // fsync is redundant for our HA model. Override with RAFT_LOG_SYNC=true
        // for stricter single-node durability.
        ro.setSync(
                Boolean.parseBoolean(System.getenv().getOrDefault("RAFT_LOG_SYNC", "false")));
        ro.setMaxByteCountPerRpc(
                Integer.parseInt(System.getenv().getOrDefault("RAFT_MAX_BYTES_PER_RPC", "1048576")));
    }

    public boolean init() {
        log.info("Starting Raft node: {} in group: {}", serverId, groupId);
        this.raftGroupService = new RaftGroupService(groupId, serverId, nodeOptions);

        int maxRetries = 6;
        for (int attempt = 1; ; attempt++) {
            try {
                this.node = raftGroupService.start();
                if (this.node != null) {
                    log.info("Raft node started: {}", serverId);
                    return true;
                }
                return false;
            } catch (Exception e) {
                if (attempt >= maxRetries) {
                    log.error("Failed to start Raft node after {} attempts: {}", maxRetries, e.getMessage());
                    throw e;
                }
                long delayMs = Math.min(attempt * 2000L, 10000L);
                log.warn("Raft node start attempt {}/{} failed: {} — retrying in {}ms",
                        attempt, maxRetries, e.getMessage(), delayMs);
                try { Thread.sleep(delayMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
            }
        }
    }

    /**
     * Submit a command through the Raft log. Blocks until the command is committed
     * and applied on this node (and replicated to a quorum).
     *
     * Timeout default 5 seconds — under saturation, longer waits just block the
     * caller thread without recovering: the apply queue is FIFO so a request
     * waiting 30s would still get applied eventually, but the caller has likely
     * given up. 5s lets the caller surface a clean error and frees the servlet
     * thread back to the pool. Override via LEDGER_RAFT_SUBMIT_TIMEOUT_MS env.
     */
    private static final long SUBMIT_TIMEOUT_MS =
            Long.parseLong(System.getenv().getOrDefault("LEDGER_RAFT_SUBMIT_TIMEOUT_MS", "5000"));

    public CommandResult submit(RaftCommand command) {
        CompletableFuture<CommandResult> future = new CompletableFuture<>();
        pendingCommands.put(command.requestId(), future);
        byte[] data = CommandSerializer.serialize(command);
        Task task = new Task(ByteBuffer.wrap(data), status -> {
            if (!status.isOk()) {
                future.completeExceptionally(new RuntimeException("Raft apply failed: " + status));
                pendingCommands.remove(command.requestId());
            }
        });
        this.node.apply(task);
        try {
            return future.get(SUBMIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            pendingCommands.remove(command.requestId());
            throw new RuntimeException("Raft command timeout: " + command.requestId(), e);
        }
    }

    public Node getNode() { return node; }
    public LedgerRaftStateMachine getStateMachine() { return stateMachine; }
    public PeerId getServerId() { return serverId; }
    public boolean isLeader() { return node != null && node.isLeader(); }

    public String getLeaderEndpoint() {
        if (node == null) return "unknown";
        PeerId leader = node.getLeaderId();
        return leader != null ? leader.toString() : "unknown";
    }

    @Override
    public void close() {
        if (node != null) {
            try {
                node.shutdown();
            } catch (Exception e) {
                log.warn("Node shutdown error: {}", e.getMessage());
            }
        }
        if (raftGroupService != null) {
            try {
                raftGroupService.shutdown();
            } catch (Exception e) {
                log.warn("RaftGroupService shutdown error: {}", e.getMessage());
            }
        }
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

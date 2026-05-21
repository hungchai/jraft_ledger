package com.tomma8.ledger.raft;

import com.alipay.sofa.jraft.Node;
import com.alipay.sofa.jraft.RaftGroupService;
import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.conf.Configuration;
import com.alipay.sofa.jraft.entity.PeerId;
import com.alipay.sofa.jraft.entity.Task;
import com.alipay.sofa.jraft.option.NodeOptions;
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
     */
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
            return future.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            pendingCommands.remove(command.requestId());
            throw new RuntimeException("Raft command timeout: " + command.requestId(), e);
        }
    }

    public Node getNode() { return node; }
    public LedgerRaftStateMachine getStateMachine() { return stateMachine; }
    public PeerId getServerId() { return serverId; }
    public boolean isLeader() { return node != null && node.isLeader(); }

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

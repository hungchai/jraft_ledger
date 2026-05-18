package com.ibank.ledger.raft;

import com.alipay.sofa.jraft.Node;
import com.alipay.sofa.jraft.RaftGroupService;
import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.conf.Configuration;
import com.alipay.sofa.jraft.entity.PeerId;
import com.alipay.sofa.jraft.entity.Task;
import com.alipay.sofa.jraft.option.NodeOptions;
import com.ibank.ledger.domain.command.CommandResult;
import com.ibank.ledger.domain.command.RaftCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

/**
 * Manages a single SOFAJRaft node lifecycle.
 */
public class RaftNodeManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RaftNodeManager.class);

    private final String groupId;
    private final PeerId serverId;
    private final Configuration conf;
    private final NodeOptions nodeOptions;
    private final LedgerRaftStateMachine stateMachine;

    private RaftGroupService raftGroupService;
    private Node node;

    public RaftNodeManager(String groupId, String serverIdStr, String peerList,
                            String dataPath, LedgerRaftStateMachine stateMachine) {
        this.groupId = groupId;
        this.serverId = new PeerId();
        this.serverId.parse(serverIdStr);
        this.conf = new Configuration();
        this.conf.parse(peerList);
        this.stateMachine = stateMachine;

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
        this.node = raftGroupService.start();
        if (this.node != null) {
            log.info("Raft node started: {}", serverId);
            return true;
        }
        log.error("Failed to start Raft node: {}", serverId);
        return false;
    }

    public CompletableFuture<CommandResult> submit(RaftCommand command) {
        CompletableFuture<CommandResult> future = new CompletableFuture<>();
        byte[] data = CommandSerializer.serialize(command);
        Task task = new Task(ByteBuffer.wrap(data), status -> {
            if (status.isOk()) {
                future.complete(null);
            } else {
                future.completeExceptionally(new RuntimeException("Raft apply failed: " + status));
            }
        });
        this.node.apply(task);
        return future;
    }

    public Node getNode() { return node; }
    public LedgerRaftStateMachine getStateMachine() { return stateMachine; }
    public PeerId getServerId() { return serverId; }

    public boolean isLeader() {
        return node != null && node.isLeader();
    }

    @Override
    public void close() {
        if (raftGroupService != null) raftGroupService.shutdown();
    }
}

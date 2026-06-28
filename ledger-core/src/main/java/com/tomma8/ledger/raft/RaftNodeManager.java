package com.tomma8.ledger.raft;

import com.alipay.sofa.jraft.JRaftServiceFactory;
import com.alipay.sofa.jraft.Node;
import com.alipay.sofa.jraft.RaftGroupService;
import com.alipay.sofa.jraft.conf.Configuration;
import com.alipay.sofa.jraft.entity.PeerId;
import com.alipay.sofa.jraft.entity.Task;
import com.alipay.sofa.jraft.option.NodeOptions;
import com.alipay.sofa.jraft.option.RaftOptions;
import com.alipay.sofa.jraft.storage.LogStorage;
import com.tomma8.ledger.domain.command.BatchRaftCommand;
import com.tomma8.ledger.domain.command.CommandResult;
import com.tomma8.ledger.domain.command.RaftCommand;
import com.tomma8.ledger.wal.ChronicleWalPruner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class RaftNodeManager implements ConsensusEngine {

    private static final Logger log = LoggerFactory.getLogger(RaftNodeManager.class);

    private final String groupId;
    private final PeerId serverId;
    private final NodeOptions nodeOptions;
    private final LedgerRaftStateMachine stateMachine;

    private RaftGroupService raftGroupService;
    private Node node;
    private ChronicleWalPruner pruner;
    // Strong reference to the custom LogStorage so it isn't GC'd while the
    // JRaftServiceFactory closure holds only a weak ref. Also used to close
    // the LogStorage on shutdown (if non-null).
    @SuppressWarnings("unused")
    private LogStorage customLogStorage;

    // Map of requestId → future, populated by submit(), completed by onApply()
    final ConcurrentHashMap<String, CompletableFuture<CommandResult>> pendingCommands = new ConcurrentHashMap<>();

    public RaftNodeManager(String groupId, String serverIdStr, String peerList,
                            String dataPath, LedgerRaftStateMachine stateMachine) {
        this(groupId, serverIdStr, peerList, dataPath, stateMachine, true, null, null);
    }

    public RaftNodeManager(String groupId, String serverIdStr, String peerList,
                            String dataPath, LedgerRaftStateMachine stateMachine,
                            boolean raftLogSync) {
        this(groupId, serverIdStr, peerList, dataPath, stateMachine, raftLogSync, null, null);
    }

    /**
     * Full constructor. {@code customLogStorage} overrides the default
     * (SOFAJRaft built-in RocksDB-backed) LogStorage. When non-null, the
     * Chronicle WAL is used (or whichever impl the caller passes). The pruner
     * starts after the Raft node has initialized the custom LogStorage.
     */
    public RaftNodeManager(String groupId, String serverIdStr, String peerList,
                            String dataPath, LedgerRaftStateMachine stateMachine,
                            boolean raftLogSync,
                            LogStorage customLogStorage,
                            ChronicleWalPruner pruner) {
        this.groupId = groupId;
        this.serverId = new PeerId();
        this.serverId.parse(serverIdStr);
        Configuration conf = new Configuration();
        conf.parse(peerList);
        this.stateMachine = stateMachine;
        stateMachine.setPendingCommands(pendingCommands);

        this.nodeOptions = new NodeOptions();
        // Raft log fsync — RaftOptions.sync controls whether LogStorage fsyncs the WAL
        // on every append. Disable ONLY for ephemeral/dev runs; production clusters MUST
        // keep this on (default true) so a leader crash before replication is detectable
        // via the log.
        RaftOptions raftOptions = new RaftOptions();
        raftOptions.setSync(raftLogSync);
        nodeOptions.setRaftOptions(raftOptions);
        nodeOptions.setElectionTimeoutMs(3000);
        nodeOptions.setInitialConf(conf);
        nodeOptions.setDisableCli(false);
        nodeOptions.setSnapshotIntervalSecs(600);
        nodeOptions.setLogUri(dataPath + File.separator + "log");
        nodeOptions.setRaftMetaUri(dataPath + File.separator + "raft_meta");
        nodeOptions.setSnapshotUri(dataPath + File.separator + "snapshot");
        nodeOptions.setFsm(this.stateMachine);
        if (customLogStorage != null) {
            // SOFAJRaft 1.4.0 has no setLogStorage; instead install a JRaftServiceFactory
            // that returns our custom LogStorage from createLogStorage().
            this.customLogStorage = customLogStorage;
            final JRaftServiceFactory defaultFactory = nodeOptions.getServiceFactory();
            nodeOptions.setServiceFactory(new JRaftServiceFactory() {
                @Override
                public LogStorage createLogStorage(String uri, RaftOptions opts) {
                    return customLogStorage;
                }
                @Override
                public com.alipay.sofa.jraft.storage.RaftMetaStorage createRaftMetaStorage(String uri, RaftOptions opts) {
                    return defaultFactory.createRaftMetaStorage(uri, opts);
                }
                @Override
                public com.alipay.sofa.jraft.storage.SnapshotStorage createSnapshotStorage(com.alipay.sofa.jraft.option.NodeOptions nodeOpts) {
                    return defaultFactory.createSnapshotStorage(nodeOpts);
                }
                @Override
                public com.alipay.sofa.jraft.entity.codec.LogEntryCodecFactory createLogEntryCodecFactory() {
                    return defaultFactory.createLogEntryCodecFactory();
                }
            });
            log.info("Raft log fsync (NodeOptions.setSync) = {} — using custom LogStorage: {}",
                    raftLogSync, customLogStorage.getClass().getSimpleName());
        } else {
            log.info("Raft log fsync (NodeOptions.setSync) = {} — using default (SOFAJRaft RocksDB-backed) LogStorage",
                    raftLogSync);
        }
        this.pruner = pruner;
    }

    public boolean init() {
        log.info("Starting Raft node: {} in group: {}", serverId, groupId);
        this.raftGroupService = new RaftGroupService(groupId, serverId, nodeOptions);

        int maxRetries = 6;
        for (int attempt = 1; ; attempt++) {
            try {
                this.node = raftGroupService.start();
                if (this.node != null) {
                    // Wire Node into the FSM so it can recover missing log
                    // entries via reflection (see LedgerRaftStateMachine.setNode)
                    this.stateMachine.setNode(this.node);
                    if (this.pruner != null) {
                        this.pruner.start();
                    }
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
     * 4-segment timeline:
     * - S1 (submit → quorum committed): node.apply() → TaskClosure.onCommitted()
     * - S2 (committed → apply ready): onCommitted() → onApply() start (FSM queue wait)
     * - S3 (apply): onApply() start → future.complete() (deser + state machine + future)
     * - S4 (return): future.complete() → future.get() returns (HTTP thread wake + response)
     */
    public CommandResult submit(RaftCommand command) {
        long tSubmit = System.nanoTime();
        CompletableFuture<CommandResult> future = new CompletableFuture<>();
        pendingCommands.put(command.requestId(), future);
        // Stamp apply time once here on the leader so it replicates in the log entry.
        byte[] data = CommandSerializer.serialize(command, System.currentTimeMillis());

        Task task = new Task(ByteBuffer.wrap(data), status -> {
            if (!status.isOk()) {
                future.completeExceptionally(new RuntimeException("Raft apply failed: " + status));
                pendingCommands.remove(command.requestId());
            }
        });

        this.node.apply(task);
        long tApplied = System.nanoTime(); // S1 end: task enqueued to Raft Disruptor
        com.tomma8.ledger.metrics.LedgerMetrics.recordRaftEnqueue(tApplied - tSubmit);
        try {
            long tWaitStart = System.nanoTime();
            CommandResult result = future.get(10, TimeUnit.SECONDS);
            long tWaitEnd = System.nanoTime();
            com.tomma8.ledger.metrics.LedgerMetrics.recordRaftWaitApply(tWaitEnd - tWaitStart);
            long tReturn = System.nanoTime();
            com.tomma8.ledger.metrics.LedgerMetrics.recordRaftWakeup(tReturn - tWaitEnd);
            com.tomma8.ledger.metrics.LedgerMetrics.recordRaftTotal(tReturn - tSubmit);
            long totalMs = (tReturn - tSubmit) / 1_000_000;
            long raftInternalMs = (tApplied - tSubmit) / 1_000_000;
            // S4 = total - S3 (S3 logged in LedgerRaftStateMachine.onApply)
            if (totalMs > 50) {
                log.info("[SUBMIT_TIMING] requestId={} total={}ms raftInternal={}ms",
                        command.requestId(), totalMs, raftInternalMs);
            }
            return result;
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - tSubmit) / 1_000_000;
            com.tomma8.ledger.metrics.LedgerMetrics.recordRaftTotal(elapsedMs * 1_000_000L);
            log.error("Raft command timeout: requestId={} elapsedMs={}", command.requestId(), elapsedMs);
            pendingCommands.remove(command.requestId());
            throw new RuntimeException("Raft command timeout: " + command.requestId() + " after " + elapsedMs + "ms", e);
        }
    }

    @Override
    public List<CommandResult> submitBatch(List<RaftCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            throw new IllegalArgumentException("commands must not be empty");
        }
        if (commands.size() == 1) {
            return List.of(submit(commands.get(0)));
        }

        long tSubmit = System.nanoTime();
        List<CompletableFuture<CommandResult>> futures = new ArrayList<>(commands.size());
        for (RaftCommand command : commands) {
            CompletableFuture<CommandResult> future = new CompletableFuture<>();
            pendingCommands.put(command.requestId(), future);
            futures.add(future);
        }

        BatchRaftCommand batch = BatchRaftCommand.of(commands);
        // One leader-stamped apply time for the whole batch (all sub-commands commit together).
        byte[] data = CommandSerializer.serialize(batch, System.currentTimeMillis());
        Task task = new Task(ByteBuffer.wrap(data), status -> {
            if (!status.isOk()) {
                RuntimeException ex = new RuntimeException("Raft batch apply failed: " + status);
                for (RaftCommand command : commands) {
                    CompletableFuture<CommandResult> future = pendingCommands.remove(command.requestId());
                    if (future != null) future.completeExceptionally(ex);
                }
            }
        });

        this.node.apply(task);
        long tApplied = System.nanoTime();
        com.tomma8.ledger.metrics.LedgerMetrics.recordRaftEnqueue(tApplied - tSubmit);
        com.tomma8.ledger.metrics.LedgerMetrics.recordRaftBatchSize(commands.size());

        try {
            long tWaitStart = System.nanoTime();
            List<CommandResult> results = new ArrayList<>(commands.size());
            for (int i = 0; i < futures.size(); i++) {
                results.add(futures.get(i).get(10, TimeUnit.SECONDS));
            }
            long tWaitEnd = System.nanoTime();
            com.tomma8.ledger.metrics.LedgerMetrics.recordRaftWaitApply(tWaitEnd - tWaitStart);
            long tReturn = System.nanoTime();
            com.tomma8.ledger.metrics.LedgerMetrics.recordRaftWakeup(tReturn - tWaitEnd);
            com.tomma8.ledger.metrics.LedgerMetrics.recordRaftTotal(tReturn - tSubmit);
            return results;
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - tSubmit) / 1_000_000;
            com.tomma8.ledger.metrics.LedgerMetrics.recordRaftTotal(elapsedMs * 1_000_000L);
            for (RaftCommand command : commands) {
                pendingCommands.remove(command.requestId());
            }
            throw new RuntimeException("Raft batch timeout after " + elapsedMs + "ms", e);
        }
    }

    public Node getNode() { return node; }
    public LedgerRaftStateMachine getStateMachine() { return stateMachine; }
    public PeerId getServerId() { return serverId; }
    public boolean isLeader() { return node != null && node.isLeader(); }

    // ── ConsensusEngine ────────────────────────────────────────
    @Override
    public long getLastAppliedIndex() { return stateMachine.getLastAppliedIndex(); }

    @Override
    public com.tomma8.ledger.statemachine.LedgerStateMachine getLedgerStateMachine() {
        return stateMachine.getLedgerStateMachine();
    }

    @Override
    public boolean isRunning() { return node != null; }

    @Override
    public java.util.List<String> getAlivePeers() {
        if (node == null) return java.util.List.of();
        try {
            return node.listAlivePeers().stream()
                    .filter(java.util.Objects::nonNull)
                    .map(peer -> peer.toString())
                    .toList();
        } catch (Exception e) {
            return java.util.List.of();
        }
    }

    public String getLeaderEndpoint() {
        if (node == null) return "unknown";
        PeerId leader = node.getLeaderId();
        return leader != null ? leader.toString() : "unknown";
    }

    @Override
    public void close() {
        if (pruner != null) {
            try { pruner.stop(); } catch (Exception e) { log.warn("Pruner stop error: {}", e.getMessage()); }
            pruner = null;
        }
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

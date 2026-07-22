package com.tomma8.ledger.raft;

import com.alipay.sofa.jraft.Node;
import com.alipay.sofa.jraft.RaftGroupService;
import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.conf.Configuration;
import com.alipay.sofa.jraft.entity.PeerId;
import com.alipay.sofa.jraft.entity.Task;
import com.alipay.sofa.jraft.option.NodeOptions;
import com.alipay.sofa.jraft.option.RaftOptions;
import com.tomma8.ledger.domain.command.BatchRaftCommand;
import com.tomma8.ledger.domain.command.CommandResult;
import com.tomma8.ledger.domain.command.RaftCommand;
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

    // Map of requestId → future, populated by submit(), completed by onApply()
    final ConcurrentHashMap<String, CompletableFuture<CommandResult>> pendingCommands = new ConcurrentHashMap<>();

    public RaftNodeManager(String groupId, String serverIdStr, String peerList,
                            String dataPath, LedgerRaftStateMachine stateMachine) {
        this(groupId, serverIdStr, peerList, dataPath, stateMachine, true);
    }

    public RaftNodeManager(String groupId, String serverIdStr, String peerList,
                            String dataPath, LedgerRaftStateMachine stateMachine,
                            boolean raftLogSync) {
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
        registerBoundedLogStorageOptions();
        nodeOptions.setElectionTimeoutMs(3000);
        nodeOptions.setInitialConf(conf);
        nodeOptions.setDisableCli(false);
        nodeOptions.setSnapshotIntervalSecs(600);
        // Throttle snapshot disk IO. The snapshot save streams the full journal history
        // (grows without bound) onto the same NVMe as the raft-log fsync; unthrottled it
        // saturates the device (~470MB/s bursts, w_await up to 5.9s) and starves the
        // fsync path — measured as SLOW_APPLY (persist max 3.7s), latency decay from
        // the first snapshot (~T+20min) onward, and leader elections under load.
        // 64MB/s keeps the device responsive; the snapshot just takes proportionally longer.
        nodeOptions.setSnapshotThrottle(new com.alipay.sofa.jraft.storage.snapshot.ThroughputSnapshotThrottle(
                64L * 1024 * 1024, 10));
        nodeOptions.setLogUri(dataPath + File.separator + "log");
        nodeOptions.setRaftMetaUri(dataPath + File.separator + "raft_meta");
        nodeOptions.setSnapshotUri(dataPath + File.separator + "snapshot");
        nodeOptions.setFsm(this.stateMachine);
        log.info("Raft log fsync (NodeOptions.setSync) = {}", raftLogSync);
    }

    // StorageOptionsFactory registration is process-global and throws on re-register;
    // guard so multi-node tests (several RaftNodeManagers per JVM) register once.
    private static final java.util.concurrent.atomic.AtomicBoolean LOG_STORE_OPTS_REGISTERED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Bound the native memory of jraft's OWN RocksDB (the log store). Its stock options
     * leave max_open_files unlimited and keep index/filter blocks outside any cache
     * budget, so table-reader memory grows with SST count until the container OOMs
     * (part of the ~20MB/min native creep measured under 3k TPS sustained).
     */
    private static void registerBoundedLogStorageOptions() {
        if (!LOG_STORE_OPTS_REGISTERED.compareAndSet(false, true)) return;
        org.rocksdb.DBOptions logDbOpts = com.alipay.sofa.jraft.util.StorageOptionsFactory
                .getDefaultRocksDBOptions();
        logDbOpts.setMaxOpenFiles(128);
        com.alipay.sofa.jraft.util.StorageOptionsFactory.registerRocksDBOptions(
                com.alipay.sofa.jraft.storage.impl.RocksDBLogStorage.class, logDbOpts);
        org.rocksdb.BlockBasedTableConfig logTableConfig = com.alipay.sofa.jraft.util.StorageOptionsFactory
                .getDefaultRocksDBTableConfig()
                .setBlockCache(new org.rocksdb.LRUCache(64L * 1024 * 1024))
                .setCacheIndexAndFilterBlocks(true)
                .setPinL0FilterAndIndexBlocksInCache(true);
        com.alipay.sofa.jraft.util.StorageOptionsFactory.registerRocksDBTableFormatConfig(
                com.alipay.sofa.jraft.storage.impl.RocksDBLogStorage.class, logTableConfig);
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

    /**
     * Non-blocking submit: append ONE Raft log entry for the batch and return per-command futures
     * that complete on apply (via onApply → pendingCommands). Does NOT wait for quorum commit, so
     * the caller can keep multiple batches in flight (pipelining). Raft still totally orders entries
     * (node.apply calls are sequential from the single command-queue worker); results are matched
     * back by requestId, so out-of-order completion is safe.
     */
    public List<CompletableFuture<CommandResult>> submitBatchAsync(List<RaftCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            throw new IllegalArgumentException("commands must not be empty");
        }
        long tSubmit = System.nanoTime();
        List<CompletableFuture<CommandResult>> futures = new ArrayList<>(commands.size());
        for (RaftCommand command : commands) {
            CompletableFuture<CommandResult> future = new CompletableFuture<>();
            // Record submit → apply-complete as the raft-commit stage. In the pipelined path this is
            // the only place raft.total is captured (blocking submitBatch records it separately); without
            // this, ledger.raft.total has zero samples under pipelineDepth>1.
            future.whenComplete((r, e) ->
                    com.tomma8.ledger.metrics.LedgerMetrics.recordRaftTotal(System.nanoTime() - tSubmit));
            pendingCommands.put(command.requestId(), future);
            futures.add(future);
        }
        // size==1 keeps the single-command wire format (matches the onApply single path);
        // size>1 uses BatchRaftCommand. One leader-stamped apply time for the whole entry.
        byte[] data = commands.size() == 1
                ? CommandSerializer.serialize(commands.get(0), System.currentTimeMillis())
                : CommandSerializer.serialize(BatchRaftCommand.of(commands), System.currentTimeMillis());
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
        com.tomma8.ledger.metrics.LedgerMetrics.recordRaftEnqueue(System.nanoTime() - tSubmit);
        com.tomma8.ledger.metrics.LedgerMetrics.recordRaftBatchSize(commands.size());
        return futures;
    }

    @Override
    public List<CommandResult> submitBatch(List<RaftCommand> commands) {
        long tSubmit = System.nanoTime();
        List<CompletableFuture<CommandResult>> futures = submitBatchAsync(commands);
        try {
            long tWaitStart = System.nanoTime();
            List<CommandResult> results = new ArrayList<>(commands.size());
            for (CompletableFuture<CommandResult> f : futures) {
                results.add(f.get(10, TimeUnit.SECONDS));
            }
            long tWaitEnd = System.nanoTime();
            com.tomma8.ledger.metrics.LedgerMetrics.recordRaftWaitApply(tWaitEnd - tWaitStart);
            com.tomma8.ledger.metrics.LedgerMetrics.recordRaftWakeup(0);
            // raft.total recorded per-command via whenComplete in submitBatchAsync (avoids double-count).
            return results;
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - tSubmit) / 1_000_000;
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
            return node.listAlivePeers().stream().map(PeerId::toString).toList();
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

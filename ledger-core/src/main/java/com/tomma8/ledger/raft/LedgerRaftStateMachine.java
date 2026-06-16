package com.tomma8.ledger.raft;

import com.alipay.sofa.jraft.Closure;
import com.alipay.sofa.jraft.Iterator;
import com.alipay.sofa.jraft.Node;
import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.core.StateMachineAdapter;
import com.alipay.sofa.jraft.entity.LeaderChangeContext;
import com.alipay.sofa.jraft.error.RaftError;
import com.alipay.sofa.jraft.storage.LogManager;
import com.alipay.sofa.jraft.entity.LogEntry;
import com.alipay.sofa.jraft.storage.snapshot.SnapshotReader;
import com.alipay.sofa.jraft.storage.snapshot.SnapshotWriter;
import com.tomma8.ledger.domain.command.AccountAddBalanceTypeCommand;
import com.tomma8.ledger.domain.command.AccountCloseCommand;
import com.tomma8.ledger.domain.command.AccountCreateCommand;
import com.tomma8.ledger.domain.command.AccountFreezeCommand;
import com.tomma8.ledger.domain.command.AdjustmentCommand;
import com.tomma8.ledger.domain.command.CommandResult;
import com.tomma8.ledger.domain.command.PostingCommand;
import com.tomma8.ledger.domain.command.RaftCommand;
import com.tomma8.ledger.domain.command.ReversalCommand;
import com.tomma8.ledger.domain.model.LedgerErrorCode;
import com.tomma8.ledger.statemachine.LedgerStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * SOFAJRaft StateMachine adapter wrapping our LedgerStateMachine.
 */
public class LedgerRaftStateMachine extends StateMachineAdapter {

    private static final Logger log = LoggerFactory.getLogger(LedgerRaftStateMachine.class);

    private final LedgerStateMachine ledgerStateMachine;
    private final AtomicLong leaderTerm = new AtomicLong(-1);
    private final AtomicLong lastAppliedIndex = new AtomicLong(0);
    private volatile boolean isLeader;
    private NodeRole nodeRole;
    private Node node;
    // Resolved via reflection from NodeImpl.logManager (SOFAJRaft 1.4.0+).
    // Allows recovering full entries when replicator delivers empty payloads.
    private volatile LogManager logManager;
    private java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<CommandResult>> pendingCommands;

    // Guards snapshot save/load against concurrent onApply to prevent
    // capturing partial state (e.g. one balance updated but not the other).
    private final ReentrantReadWriteLock snapshotLock = new ReentrantReadWriteLock();

    // Reusable deserialization buffer to avoid per-command byte[] allocation on hot path
    private static final int DESER_BUFFER_SIZE = 16384;
    private static final ThreadLocal<byte[]> DESER_BUFFER =
            ThreadLocal.withInitial(() -> new byte[DESER_BUFFER_SIZE]);

    public LedgerRaftStateMachine(LedgerStateMachine ledgerStateMachine) {
        this.ledgerStateMachine = ledgerStateMachine;
        this.ledgerStateMachine.setLastAppliedIndexSource(this.lastAppliedIndex::get);
    }

    /**
     * Wire the Raft Node so we can recover missing log entries via reflection.
     * Called from RaftNodeManager after raftGroupService.start() succeeds.
     */
    public void setNode(Node node) {
        this.node = node;
        this.logManager = resolveLogManager(node);
        if (this.logManager != null) {
            log.info("[RECOVERY_READY] LogManager resolved via reflection — empty-payload entries can be recovered from local log storage");
        } else {
            log.warn("[RECOVERY_DEGRADED] Could not resolve LogManager via reflection — empty-payload entries will be skipped (divergence risk)");
        }
    }

    private static LogManager resolveLogManager(Node node) {
        if (node == null) return null;
        try {
            // SOFAJRaft 1.4.0: private LogManager logManager; on NodeImpl
            java.lang.reflect.Field f = node.getClass().getDeclaredField("logManager");
            f.setAccessible(true);
            return (LogManager) f.get(node);
        } catch (NoSuchFieldException e) {
            log.warn("[RECOVERY_REFLECT_FAIL] NoSuchField 'logManager' on {} — SOFAJRaft version may differ from 1.4.0", node.getClass().getName());
        } catch (Throwable t) {
            log.warn("[RECOVERY_REFLECT_FAIL] reflection error: {}", t.toString());
        }
        return null;
    }

    /**
     * Try to recover the full log entry from local LogStorage (RocksDB) when
     * the replicator delivered an empty payload. Returns null if recovery is
     * not possible (LogManager not wired or entry not yet flushed).
     */
    private LogEntry tryRecoverEntry(long index) {
        if (logManager == null) return null;
        try {
            LogEntry entry = logManager.getEntry(index);
            if (entry == null) {
                log.debug("[RECOVERY_NULL] index={} LogManager returned null", index);
                return null;
            }
            return entry;
        } catch (Throwable t) {
            // IndexOutOfBounds / log compacted — not recoverable
            log.debug("[RECOVERY_FAIL] index={} {}", index, t.getClass().getSimpleName());
            return null;
        }
    }

    public long getLastAppliedIndex() {
        return lastAppliedIndex.get();
    }

    public void setPendingCommands(java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<CommandResult>> pendingCommands) {
        this.pendingCommands = pendingCommands;
    }

    public LedgerRaftStateMachine withNodeRole(NodeRole nr) {
        this.nodeRole = nr;
        return this;
    }

    public LedgerStateMachine getLedgerStateMachine() {
        return ledgerStateMachine;
    }

    public boolean isLeader() {
        return isLeader;
    }

    @Override
    public void onApply(Iterator iter) {
        snapshotLock.readLock().lock();
        try {
            while (iter.hasNext()) {
                long index = iter.getIndex();
                Closure done = iter.done();
                // CRITICAL: duplicate() protects the replicator's shared ByteBuffer.
                // Without it get() advances position → replicator ships empty bytes → divergence.
                ByteBuffer raw = iter.getData();
                ByteBuffer data = raw != null ? raw.duplicate() : null;

                if (data == null || !data.hasRemaining()) {
                    LogEntry recovered = tryRecoverEntry(index);
                    if (recovered != null && recovered.getData() != null && recovered.getData().hasRemaining()) {
                        log.warn("[RECOVERY] index={} len={} — empty payload recovered from LogManager",
                                index, recovered.getData().remaining());
                        data = recovered.getData();
                    } else {
                        log.error("[SKIP_EMPTY] index={} — empty entry, no recovery", index);
                        lastAppliedIndex.set(index);
                        iter.next();
                        continue;
                    }
                }

                int len = data.remaining();
                byte[] bytes = len <= DESER_BUFFER_SIZE ? DESER_BUFFER.get() : new byte[len];
                data.get(bytes, 0, len);

                long tStart = System.nanoTime();
                try {
                    RaftCommand cmd = CommandSerializer.deserialize(bytes, len);
                    long tDeserEnd = System.nanoTime();
                    com.tomma8.ledger.metrics.LedgerMetrics.recordApplyDeserialize(tDeserEnd - tStart);

                    if (log.isDebugEnabled()) {
                        log.debug("[APPLY] index={} cmd={} reqId={} len={} deser={}us",
                                index, cmd.getClass().getSimpleName(), cmd.requestId(),
                                len, (tDeserEnd - tStart) / 1000);
                    }

                    CommandResult result = executeCommand(cmd, index);
                    long tExecEnd = System.nanoTime();
                    com.tomma8.ledger.metrics.LedgerMetrics.recordApplyTotal(tExecEnd - tStart);

                    if (pendingCommands != null) {
                        var future = pendingCommands.remove(cmd.requestId());
                        if (future != null) future.complete(result);
                    }
                    if (done != null) {
                        done.run(result.isCompleted() ? Status.OK()
                                : new Status(RaftError.EBUSY, result.errorCodes().toString()));
                    }

                    long totalUs = (tExecEnd - tStart) / 1000;
                    // APPLY_SLOW warn disabled: onApply is the Raft apply hot path
                    // and the string formatting + log dispatch on the slow path itself
                    // made contention worse during stress tests. Metrics are still
                    // recorded above (recordApplyTotal); use Prometheus to inspect.
                    if (false && totalUs > 5000) {
                        log.warn("[APPLY_SLOW] cmd={} index={} total={}us",
                                cmd.getClass().getSimpleName(), index, totalUs);
                    }
                } catch (Exception e) {
                    log.error("[APPLY_FAIL] index={} len={}", index, len, e);
                    if (pendingCommands != null) {
                        try {
                            RaftCommand cmd = CommandSerializer.deserialize(bytes, len);
                            var future = pendingCommands.remove(cmd.requestId());
                            if (future != null) {
                                future.complete(CommandResult.rejected(LedgerErrorCode.RAFT_APPLY_ERROR));
                            }
                        } catch (Exception ignored) {}
                    }
                    if (done != null) {
                        done.run(new Status(RaftError.EIO, e.getMessage()));
                    }
                }

                lastAppliedIndex.set(index);
                iter.next();
            }
        } finally {
            snapshotLock.readLock().unlock();
        }
    }

    private CommandResult executeCommand(RaftCommand cmd, long raftIndex) {
        if (cmd instanceof AdjustmentCommand a) {
            return ledgerStateMachine.applyAdjustment(a, raftIndex);
        }
        if (cmd instanceof PostingCommand p) {
            return ledgerStateMachine.applyPosting(p, raftIndex);
        }
        if (cmd instanceof ReversalCommand r) {
            return ledgerStateMachine.applyReversal(r, raftIndex);
        }
        if (cmd instanceof AccountCreateCommand a) {
            return ledgerStateMachine.applyAccountCreate(a, raftIndex);
        }
        if (cmd instanceof AccountFreezeCommand f) {
            return f.freeze() ? ledgerStateMachine.applyFreeze(f, raftIndex)
                    : ledgerStateMachine.applyUnfreeze(f, raftIndex);
        }
        if (cmd instanceof AccountCloseCommand c) {
            return ledgerStateMachine.applyCloseAccount(c.accountId(), c.requestId(), raftIndex);
        }
        if (cmd instanceof AccountAddBalanceTypeCommand a) {
            return ledgerStateMachine.applyAddBalanceType(a.accountId(), a.balanceType(), a.currency(), a.requestId(), raftIndex);
        }
        throw new IllegalArgumentException("Unknown command: " + cmd.getClass().getName());
    }

    @Override
    public void onSnapshotSave(SnapshotWriter writer, Closure done) {
        snapshotLock.writeLock().lock();
        try {
            byte[] data = ledgerStateMachine.snapshotBytes();
            String filePath = writer.getPath() + File.separator + "state_machine_snapshot";
            java.nio.file.Files.write(java.nio.file.Paths.get(filePath), data);
            writer.addFile("state_machine_snapshot");
            ledgerStateMachine.takeSnapshot(); // also persist to local RocksDB
            done.run(Status.OK());
        } catch (Exception e) {
            log.error("Snapshot save failed", e);
            done.run(new Status(RaftError.EIO, e.getMessage()));
        } finally {
            snapshotLock.writeLock().unlock();
        }
    }

    @Override
    public boolean onSnapshotLoad(SnapshotReader reader) {
        snapshotLock.writeLock().lock();
        try {
            if (reader.listFiles() != null && !reader.listFiles().isEmpty()) {
                String filePath = reader.getPath() + File.separator + "state_machine_snapshot";
                byte[] data = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(filePath));
                ledgerStateMachine.restoreFromBytes(data);
                log.info("Snapshot loaded from leader transfer");
            } else {
                // No fallback to local RocksDB — stale local snapshot caused
                // balance divergence across nodes (non-deterministic replay).
                // Let SOFAJRaft replay from its log instead.
                log.info("No snapshot files from leader — will replay from Raft log");
            }
            return true;
        } catch (Exception e) {
            log.error("Snapshot load failed", e);
            return false;
        } finally {
            snapshotLock.writeLock().unlock();
        }
    }

    @Override
    public void onLeaderStart(long term) {
        this.leaderTerm.set(term);
        this.isLeader = true;
        if (nodeRole != null) nodeRole.setLeader(serverIdStr(), term);
        log.info("Became leader at term {}", term);
    }

    @Override
    public void onLeaderStop(Status status) {
        this.isLeader = false;
        this.leaderTerm.set(-1);
        if (nodeRole != null) nodeRole.setFollower(serverIdStr());
        log.info("Stepped down as leader: {}", status);
    }

    @Override
    public void onStartFollowing(LeaderChangeContext ctx) {
        this.isLeader = false;
        this.leaderTerm.set(ctx.getTerm());
        if (nodeRole != null) {
            nodeRole.setFollower(serverIdStr(), ctx.getTerm());
        }
        log.info("Started following leader {} at term {}", ctx.getLeaderId(), ctx.getTerm());
    }

    private String serverIdStr() {
        return nodeRole != null ? nodeRole.getNodeId() : "unknown";
    }

    private static String summarizeAmounts(RaftCommand cmd) {
        StringBuilder sb = new StringBuilder("[");
        List<PostingCommand.Leg> legs = null;
        if (cmd instanceof PostingCommand p) legs = p.legs();
        else if (cmd instanceof AdjustmentCommand a) legs = a.legs();
        if (legs != null) {
            for (int i = 0; i < legs.size(); i++) {
                if (i > 0) sb.append(",");
                var amt = legs.get(i).amount();
                sb.append(amt.toPlainString()).append("/s").append(amt.scale())
                        .append("/u").append(amt.unscaledValue().toString());
            }
        }
        return sb.append("]").toString();
    }

    public void setNodeId(String nodeId) {
        if (nodeRole != null) nodeRole.setFollower(nodeId);
    }

    // onError removed — not present in SOFAJRaft 1.3.15 StateMachineAdapter
}

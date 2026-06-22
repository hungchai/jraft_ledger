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
import com.tomma8.ledger.domain.command.BatchRaftCommand;
import com.tomma8.ledger.domain.command.CommandResult;
import com.tomma8.ledger.domain.command.PostingCommand;
import com.tomma8.ledger.domain.command.RaftApplyContext;
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
                    long applyTimeMillis = CommandSerializer.readApplyTimeMillis(bytes, len);
                    int bodyLen = len - CommandSerializer.APPLY_TIME_HEADER;
                    RaftCommand cmd = CommandSerializer.deserialize(bytes, CommandSerializer.APPLY_TIME_HEADER, bodyLen);
                    long tDeserEnd = System.nanoTime();
                    com.tomma8.ledger.metrics.LedgerMetrics.recordApplyDeserialize(tDeserEnd - tStart);

                    if (cmd instanceof BatchRaftCommand batch) {
                        applyBatch(batch, index, applyTimeMillis, done, tStart);
                    } else {
                        applySingle(cmd, index, applyTimeMillis, done, tStart, tDeserEnd);
                    }
                } catch (Exception e) {
                    log.error("[APPLY_FAIL] index={} len={}", index, len, e);
                    if (pendingCommands != null) {
                        try {
                            int bodyLen = len - CommandSerializer.APPLY_TIME_HEADER;
                            RaftCommand cmd = CommandSerializer.deserialize(bytes, CommandSerializer.APPLY_TIME_HEADER, bodyLen);
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

    private void applySingle(RaftCommand cmd, long index, long applyTimeMillis, Closure done, long tStart, long tDeserEnd) {
        if (log.isDebugEnabled()) {
            log.debug("[APPLY] index={} cmd={} reqId={} deser={}us",
                    index, cmd.getClass().getSimpleName(), cmd.requestId(),
                    (tDeserEnd - tStart) / 1000);
        }

        CommandResult result = executeCommand(cmd, RaftApplyContext.single(index, applyTimeMillis));
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
    }

    private void applyBatch(BatchRaftCommand batch, long index, long applyTimeMillis, Closure done, long tStart) {
        var commands = batch.commands();
        com.tomma8.ledger.metrics.LedgerMetrics.recordRaftBatchSize(commands.size());
        boolean allOk = true;
        for (int i = 0; i < commands.size(); i++) {
            RaftCommand sub = commands.get(i);
            RaftApplyContext ctx = RaftApplyContext.batchEntry(index, i, applyTimeMillis);
            CommandResult result = executeCommand(sub, ctx);
            if (pendingCommands != null) {
                var future = pendingCommands.remove(sub.requestId());
                if (future != null) future.complete(result);
            }
            if (!result.isCompleted()) {
                allOk = false;
            }
        }
        long tExecEnd = System.nanoTime();
        com.tomma8.ledger.metrics.LedgerMetrics.recordApplyTotal(tExecEnd - tStart);
        if (done != null) {
            done.run(allOk ? Status.OK() : new Status(RaftError.EBUSY, "batch rejected"));
        }
    }

    private CommandResult executeCommand(RaftCommand cmd, long raftIndex) {
        return executeCommand(cmd, RaftApplyContext.single(raftIndex));
    }

    private CommandResult executeCommand(RaftCommand cmd, RaftApplyContext ctx) {
        if (cmd instanceof AdjustmentCommand a) {
            return ledgerStateMachine.applyAdjustment(a, ctx);
        }
        if (cmd instanceof PostingCommand p) {
            return ledgerStateMachine.applyPosting(p, ctx);
        }
        if (cmd instanceof ReversalCommand r) {
            return ledgerStateMachine.applyReversal(r, ctx);
        }
        if (cmd instanceof AccountCreateCommand a) {
            return ledgerStateMachine.applyAccountCreate(a, ctx);
        }
        if (cmd instanceof AccountFreezeCommand f) {
            return f.freeze() ? ledgerStateMachine.applyFreeze(f, ctx.raftIndex())
                    : ledgerStateMachine.applyUnfreeze(f, ctx.raftIndex());
        }
        if (cmd instanceof AccountCloseCommand c) {
            return ledgerStateMachine.applyCloseAccount(c.accountId(), c.requestId(), ctx.raftIndex());
        }
        if (cmd instanceof AccountAddBalanceTypeCommand a) {
            return ledgerStateMachine.applyAddBalanceType(a.accountId(), a.balanceType(), a.currency(), a.requestId(), ctx.raftIndex());
        }
        throw new IllegalArgumentException("Unknown command: " + cmd.getClass().getName());
    }

    @Override
    public void onSnapshotSave(SnapshotWriter writer, Closure done) {
        // CRITICAL: this runs on the single FSM apply thread (the JRaft Disruptor that
        // also drives onApply). Any heavy work done synchronously here blocks apply for
        // its whole duration — the snapshot stall observed in the 240m soak (every 600s,
        // apply frozen for the full O(journal-count) journal stream → p99 10s, TPS→0,
        // client .get() timeouts). So we do only a tiny, bounded state capture inline and
        // offload all the heavy I/O to a background thread, returning the FSM thread to
        // applying immediately. done.run() (called from the background thread) signals
        // completion back to JRaft — its documented async-snapshot contract.

        // 1. Capture the mutable in-memory state inline (bounded by account count → sub-ms).
        //    This is the only part that must be consistent w.r.t. apply; taken on the FSM
        //    thread it is naturally exclusive with onApply (same thread).
        final byte[] smData;
        snapshotLock.writeLock().lock();
        try {
            smData = ledgerStateMachine.snapshotBytes();
        } catch (Exception e) {
            snapshotLock.writeLock().unlock();
            log.error("Snapshot save failed (state capture)", e);
            done.run(new Status(RaftError.EIO, e.getMessage()));
            return;
        }
        snapshotLock.writeLock().unlock();

        // 2. Offload the heavy save to a background thread so apply keeps flowing.
        //    The journal CF is append-only and the RocksDB iterator pins a consistent
        //    superversion at creation, so streaming concurrently with apply is safe: it
        //    captures journals up to >= the snapshot's lastIncludedIndex (extra trailing
        //    journals are harmless — restore replays the Raft log anyway).
        com.alipay.sofa.jraft.util.Utils.runInThread(() -> {
            try {
                String filePath = writer.getPath() + File.separator + "state_machine_snapshot";
                java.nio.file.Files.write(java.nio.file.Paths.get(filePath), smData);
                writer.addFile("state_machine_snapshot");
                // Journals streamed to a separate file (not in the blob — would OOM at
                // scale). Lets an InstallSnapshot-bootstrapped follower reconstruct the
                // journal CF without the leader materializing all journals in heap.
                String journalsPath = writer.getPath() + File.separator + "journals.dat";
                try (var os = java.nio.file.Files.newOutputStream(java.nio.file.Paths.get(journalsPath))) {
                    ledgerStateMachine.streamJournalsTo(os);
                }
                writer.addFile("journals.dat");
                // Reuse the captured blob for the local sm_snapshot CF instead of re-reading
                // the (off-thread, lock-free) mutable state, which would risk a torn snapshot.
                ledgerStateMachine.persistSnapshotBlob(smData);
                done.run(Status.OK());
            } catch (Exception e) {
                log.error("Snapshot save failed", e);
                done.run(new Status(RaftError.EIO, e.getMessage()));
            }
        });
    }

    @Override
    public boolean onSnapshotLoad(SnapshotReader reader) {
        snapshotLock.writeLock().lock();
        try {
            if (reader.listFiles() != null && !reader.listFiles().isEmpty()) {
                String filePath = reader.getPath() + File.separator + "state_machine_snapshot";
                byte[] data = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(filePath));
                ledgerStateMachine.restoreFromBytes(data);
                // Restore journals (streamed separately) into the RocksDB journal CF.
                java.nio.file.Path jp = java.nio.file.Paths.get(reader.getPath() + File.separator + "journals.dat");
                if (java.nio.file.Files.exists(jp)) {
                    try (var is = java.nio.file.Files.newInputStream(jp)) {
                        ledgerStateMachine.ingestJournalsFrom(is);
                    }
                }
                // Critical: align the FSM-side lastAppliedIndex with the snapshot's
                // lastIncludedIndex. Without this, leader sees the follower's
                // lastAppliedIndex=0 forever and tries to send diff log entries
                // (instead of triggering another InstallSnapshot), keeping the
                // follower permanently out-of-sync until its local RaftDB is wiped.
                com.alipay.sofa.jraft.entity.RaftOutter.SnapshotMeta meta = reader.load();
                if (meta != null) {
                    long snapIdx = meta.getLastIncludedIndex();
                    lastAppliedIndex.set(snapIdx);
                    log.info("Snapshot loaded from leader transfer — lastAppliedIndex aligned to {} (term={})",
                            snapIdx, meta.getLastIncludedTerm());
                } else {
                    log.info("Snapshot loaded from leader transfer (no meta — lastAppliedIndex unchanged)");
                }
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

package com.tomma8.ledger.raft;

import com.alipay.sofa.jraft.Closure;
import com.alipay.sofa.jraft.Iterator;
import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.core.StateMachineAdapter;
import com.alipay.sofa.jraft.entity.LeaderChangeContext;
import com.alipay.sofa.jraft.error.RaftError;
import com.alipay.sofa.jraft.error.RaftError;
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
            ByteBuffer data = iter.getData();

            if (data != null && data.hasRemaining()) {
                int len = data.remaining();
                byte[] bytes;
                if (len <= DESER_BUFFER_SIZE) {
                    bytes = DESER_BUFFER.get();
                    data.get(bytes, 0, len);
                } else {
                    bytes = new byte[len];
                    data.get(bytes);
                }
                long tApplyStart = System.nanoTime(); // Segment 3 start
                try {
                    RaftCommand cmd = CommandSerializer.deserialize(bytes, len);
                    long tDeserEnd = System.nanoTime();

                    // Segment 3: execute state machine with deterministc Raft index
                    CommandResult result = executeCommand(cmd, index);
                    long tExecuteEnd = System.nanoTime();

                    // Segment 3 end: complete future → unblocks HTTP thread
                    if (pendingCommands != null) {
                        var future = pendingCommands.remove(cmd.requestId());
                        if (future != null) future.complete(result);
                    }

                    // done.run() signals commit acknowledgment to Raft
                    if (done != null) {
                        done.run(result.isCompleted() ? Status.OK() : new Status(RaftError.EBUSY, result.errorCodes().toString()));
                    }

                    long tDone = System.nanoTime();
                    long deserMs = (tDeserEnd - tApplyStart) / 1_000_000;
                    long executeMs = (tExecuteEnd - tDeserEnd) / 1_000_000;
                    long futureCompleteMs = (tDone - tExecuteEnd) / 1_000_000;
                    long totalApplyMs = (tDone - tApplyStart) / 1_000_000;

                    // Log detailed timing for Segment 3
                    if (totalApplyMs > 5) {
                        log.info("[APPLY_TIMING] cmd={} index={} deser={}ms exec={}ms future={}ms total={}ms",
                                cmd.getClass().getSimpleName(), index, deserMs, executeMs, futureCompleteMs, totalApplyMs);
                    }
                } catch (Exception e) {
                    log.error("Failed to apply raft command", e);
                    if (pendingCommands != null) {
                        RaftCommand cmd = null;
                        try {
                            cmd = CommandSerializer.deserialize(bytes, len);
                        } catch (Exception ignored) {}
                        if (cmd != null) {
                            var future = pendingCommands.remove(cmd.requestId());
                            if (future != null) {
                                future.complete(CommandResult.rejected(LedgerErrorCode.RAFT_APPLY_ERROR));
                            }
                        }
                    }
                    if (done != null) {
                        done.run(new Status(RaftError.EIO, e.getMessage()));
                    }
                }
            } else {
                if (done != null) {
                    done.run(Status.OK());
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

    public void setNodeId(String nodeId) {
        if (nodeRole != null) nodeRole.setFollower(nodeId);
    }

    // onError removed — not present in SOFAJRaft 1.3.15 StateMachineAdapter
}

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
                try {
                    RaftCommand cmd = CommandSerializer.deserialize(bytes, len);
                    CommandResult result = executeCommand(cmd);
                    // Propagate result back to submitter
                    if (pendingCommands != null) {
                        var future = pendingCommands.remove(cmd.requestId());
                        if (future != null) future.complete(result);
                    }
                    if (done != null) {
                        done.run(result.isCompleted() ? Status.OK() : new Status(RaftError.EBUSY, result.errorCodes().toString()));
                    }
                } catch (Exception e) {
                    log.error("Failed to apply raft command", e);
                    // Complete the pending future so the HTTP thread gets a response
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
    }

    private CommandResult executeCommand(RaftCommand cmd) {
        if (cmd instanceof AdjustmentCommand a) {
            return ledgerStateMachine.applyAdjustment(a);
        }
        if (cmd instanceof PostingCommand p) {
            return ledgerStateMachine.applyPosting(p);
        }
        if (cmd instanceof ReversalCommand r) {
            return ledgerStateMachine.applyReversal(r);
        }
        if (cmd instanceof AccountCreateCommand a) {
            return ledgerStateMachine.applyAccountCreate(a);
        }
        if (cmd instanceof AccountFreezeCommand f) {
            return f.freeze() ? ledgerStateMachine.applyFreeze(f)
                    : ledgerStateMachine.applyUnfreeze(f);
        }
        if (cmd instanceof AccountCloseCommand c) {
            return ledgerStateMachine.applyCloseAccount(c.accountId(), c.requestId());
        }
        if (cmd instanceof AccountAddBalanceTypeCommand a) {
            return ledgerStateMachine.applyAddBalanceType(a.accountId(), a.balanceType(), a.currency(), a.requestId());
        }
        throw new IllegalArgumentException("Unknown command: " + cmd.getClass().getName());
    }

    @Override
    public void onSnapshotSave(SnapshotWriter writer, Closure done) {
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
        }
    }

    @Override
    public boolean onSnapshotLoad(SnapshotReader reader) {
        try {
            // Prefer the SnapshotReader (from leader transfer), fall back to local RocksDB
            if (reader.listFiles() != null && !reader.listFiles().isEmpty()) {
                String filePath = reader.getPath() + File.separator + "state_machine_snapshot";
                byte[] data = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(filePath));
                ledgerStateMachine.restoreFromBytes(data);
                log.info("Snapshot loaded from leader transfer");
            } else {
                ledgerStateMachine.restoreFromSnapshot();
            }
            return true;
        } catch (Exception e) {
            log.error("Snapshot load failed", e);
            return false;
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

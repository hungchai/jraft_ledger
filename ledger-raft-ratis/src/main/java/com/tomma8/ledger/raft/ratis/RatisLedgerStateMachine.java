package com.tomma8.ledger.raft.ratis;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.tomma8.ledger.raft.CommandSerializer;
import com.tomma8.ledger.raft.NodeRole;
import com.tomma8.ledger.statemachine.LedgerStateMachine;
import com.tomma8.ledger.util.LedgerMappers;
import org.apache.ratis.proto.RaftProtos.LogEntryProto;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.protocol.RaftGroupMemberId;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.server.raftlog.RaftLog;
import org.apache.ratis.server.storage.RaftStorage;
import org.apache.ratis.statemachine.StateMachineStorage;
import org.apache.ratis.statemachine.TransactionContext;
import org.apache.ratis.statemachine.impl.BaseStateMachine;
import org.apache.ratis.statemachine.impl.SimpleStateMachineStorage;
import org.apache.ratis.statemachine.impl.SingleFileSnapshotInfo;
import org.apache.ratis.thirdparty.com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Apache Ratis StateMachine adapter wrapping the engine-agnostic {@link LedgerStateMachine}.
 *
 * <p>Mirror of {@code LedgerRaftStateMachine} (SOFAJRaft) — same apply / snapshot / leader
 * semantics, expressed against the Ratis {@link BaseStateMachine} API. The wrapped
 * {@link LedgerStateMachine} (business rules + RocksDB WriteBatch + snapshot bytes) is shared
 * unchanged, so balance mutations still go Leader → apply → RocksDB exactly as in production.
 *
 * <p>Command serialization reuses {@link CommandSerializer}; the {@link CommandResult} is
 * serialized as JSON into the Ratis reply {@link Message}, so the submitting client receives
 * the applied result directly (no separate pending-command map needed — Ratis blocks the
 * client until the entry is applied on the leader).
 */
public class RatisLedgerStateMachine extends BaseStateMachine {

    private static final Logger log = LoggerFactory.getLogger(RatisLedgerStateMachine.class);

    private final LedgerStateMachine ledger;
    private final NodeRole nodeRole; // nullable
    private final ObjectMapper mapper = LedgerMappers.get();
    private final SimpleStateMachineStorage storage = new SimpleStateMachineStorage();
    private final AtomicLong currentTerm = new AtomicLong(-1);

    private volatile RaftPeerId selfId;
    private volatile boolean leader;

    public RatisLedgerStateMachine(LedgerStateMachine ledger, NodeRole nodeRole) {
        this.ledger = ledger;
        this.nodeRole = nodeRole;
        this.ledger.setLastAppliedIndexSource(() -> {
            TermIndex ti = getLastAppliedTermIndex();
            return ti != null ? ti.getIndex() : 0L;
        });
    }

    public LedgerStateMachine getLedgerStateMachine() {
        return ledger;
    }

    public boolean isLeader() {
        return leader;
    }

    @Override
    public void initialize(RaftServer server, RaftGroupId groupId, RaftStorage raftStorage) throws IOException {
        super.initialize(server, groupId, raftStorage);
        this.selfId = server.getId();
        this.storage.init(raftStorage);
        loadSnapshot(storage.getLatestSnapshot());
    }

    @Override
    public void reinitialize() throws IOException {
        loadSnapshot(storage.getLatestSnapshot());
    }

    @Override
    public StateMachineStorage getStateMachineStorage() {
        return storage;
    }

    @Override
    public CompletableFuture<Message> applyTransaction(TransactionContext trx) {
        final LogEntryProto entry = trx.getLogEntry();
        final long index = entry.getIndex();
        final long term = entry.getTerm();
        final byte[] bytes = entry.getStateMachineLogEntry().getLogData().toByteArray();

        CommandResult result;
        try {
            RaftCommand cmd = CommandSerializer.deserialize(bytes, bytes.length);
            long applyTimeMillis = CommandSerializer.extractApplyTimeMillis(bytes, bytes.length);
            result = executeCommand(cmd, index, applyTimeMillis);
        } catch (Exception e) {
            log.error("[RATIS_APPLY_FAIL] index={} len={}", index, bytes.length, e);
            result = CommandResult.rejected(LedgerErrorCode.RAFT_APPLY_ERROR);
        }
        // Advance applied index AFTER state mutation so a crash between the two
        // replays the entry rather than skipping it (matches SOFAJRaft semantics).
        updateLastAppliedTermIndex(term, index);

        try {
            byte[] out = mapper.writeValueAsBytes(result);
            return CompletableFuture.completedFuture(Message.valueOf(ByteString.copyFrom(out)));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** Mirror of LedgerRaftStateMachine.executeCommand — same command → apply mapping. */
    private CommandResult executeCommand(RaftCommand cmd, long raftIndex, long applyTimeMillis) {
        Instant applyTime = Instant.ofEpochMilli(applyTimeMillis);
        if (cmd instanceof AdjustmentCommand a) {
            return ledger.applyAdjustment(a, raftIndex, applyTime);
        }
        if (cmd instanceof PostingCommand p) {
            return ledger.applyPosting(p, raftIndex, applyTime);
        }
        if (cmd instanceof ReversalCommand r) {
            return ledger.applyReversal(r, raftIndex, applyTime);
        }
        if (cmd instanceof AccountCreateCommand a) {
            return ledger.applyAccountCreate(a, raftIndex, applyTime);
        }
        if (cmd instanceof AccountFreezeCommand f) {
            return f.freeze() ? ledger.applyFreeze(f, raftIndex)
                    : ledger.applyUnfreeze(f, raftIndex);
        }
        if (cmd instanceof AccountCloseCommand c) {
            return ledger.applyCloseAccount(c.accountId(), c.requestId(), raftIndex);
        }
        if (cmd instanceof AccountAddBalanceTypeCommand a) {
            return ledger.applyAddBalanceType(a.accountId(), a.balanceType(), a.currency(), a.requestId(), raftIndex);
        }
        throw new IllegalArgumentException("Unknown command: " + cmd.getClass().getName());
    }

    @Override
    public long takeSnapshot() throws IOException {
        final TermIndex last = getLastAppliedTermIndex();
        if (last == null) {
            return RaftLog.INVALID_LOG_INDEX;
        }
        final File snapshotFile = storage.getSnapshotFile(last.getTerm(), last.getIndex());
        final byte[] data;
        try {
            data = ledger.snapshotBytes();
            Files.write(snapshotFile.toPath(), data);
            ledger.takeSnapshot(); // also persist to local RocksDB CF_SM_SNAPSHOT
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to take ledger snapshot", e);
        }
        log.info("[RATIS_SNAPSHOT] saved term={} index={} bytes={}", last.getTerm(), last.getIndex(), data.length);
        return last.getIndex();
    }

    private void loadSnapshot(SingleFileSnapshotInfo snapshot) throws IOException {
        if (snapshot == null) {
            log.info("[RATIS_SNAPSHOT] no snapshot to load — will replay from Raft log");
            return;
        }
        final File file = snapshot.getFile().getPath().toFile();
        if (!file.exists()) {
            log.warn("[RATIS_SNAPSHOT] snapshot file missing: {}", file);
            return;
        }
        final byte[] data = Files.readAllBytes(file.toPath());
        try {
            ledger.restoreFromBytes(data);
        } catch (Exception e) {
            throw new IOException("Failed to restore ledger snapshot", e);
        }
        setLastAppliedTermIndex(snapshot.getTermIndex());
        log.info("[RATIS_SNAPSHOT] loaded term={} index={} bytes={}",
                snapshot.getTermIndex().getTerm(), snapshot.getTermIndex().getIndex(), data.length);
    }

    // ── Leadership notifications — keep NodeRole accurate (REST write-gate reads it) ──

    @Override
    public void notifyLeaderChanged(RaftGroupMemberId groupMemberId, RaftPeerId newLeaderId) {
        this.leader = selfId != null && selfId.equals(newLeaderId);
        String id = selfId != null ? selfId.toString() : "unknown";
        if (nodeRole != null) {
            if (leader) {
                nodeRole.setLeader(id, currentTerm.get());
            } else {
                nodeRole.setFollower(id, currentTerm.get());
            }
        }
        log.info("[RATIS_LEADER_CHANGE] self={} newLeader={} isLeader={}", id, newLeaderId, leader);
    }

    @Override
    public void notifyTermIndexUpdated(long term, long index) {
        currentTerm.set(term);
        super.notifyTermIndexUpdated(term, index);
    }
}

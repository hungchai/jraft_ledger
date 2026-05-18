package com.ibank.ledger.raft;

import com.alipay.sofa.jraft.Closure;
import com.alipay.sofa.jraft.Iterator;
import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.core.StateMachineAdapter;
import com.alipay.sofa.jraft.entity.LeaderChangeContext;
import com.alipay.sofa.jraft.error.RaftError;
import com.alipay.sofa.jraft.storage.snapshot.SnapshotReader;
import com.alipay.sofa.jraft.storage.snapshot.SnapshotWriter;
import com.ibank.ledger.domain.command.*;
import com.ibank.ledger.statemachine.LedgerStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SOFAJRaft StateMachine adapter wrapping our LedgerStateMachine.
 */
public class LedgerRaftStateMachine extends StateMachineAdapter {

    private static final Logger log = LoggerFactory.getLogger(LedgerRaftStateMachine.class);

    private final LedgerStateMachine ledgerStateMachine;
    private final AtomicLong leaderTerm = new AtomicLong(-1);
    private volatile boolean isLeader;

    public LedgerRaftStateMachine(LedgerStateMachine ledgerStateMachine) {
        this.ledgerStateMachine = ledgerStateMachine;
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
            Closure done = iter.done();
            ByteBuffer data = iter.getData();

            if (data != null && data.hasRemaining()) {
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);
                try {
                    RaftCommand cmd = CommandSerializer.deserialize(bytes);
                    CommandResult result = executeCommand(cmd);
                    if (done != null) {
                        done.run(result.isCompleted() ? Status.OK() : new Status(RaftError.EBUSY, String.join(",", result.errorCodes())));
                    }
                } catch (Exception e) {
                    log.error("Failed to apply raft command", e);
                    if (done != null) {
                        done.run(new Status(RaftError.EIO, e.getMessage()));
                    }
                }
            } else {
                if (done != null) {
                    done.run(Status.OK());
                }
            }
            iter.next();
        }
    }

    private CommandResult executeCommand(RaftCommand cmd) {
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
        throw new IllegalArgumentException("Unknown command: " + cmd.getClass().getName());
    }

    @Override
    public void onSnapshotSave(SnapshotWriter writer, Closure done) {
        try {
            ledgerStateMachine.takeSnapshot();
            done.run(Status.OK());
        } catch (Exception e) {
            log.error("Snapshot save failed", e);
            done.run(new Status(RaftError.EIO, e.getMessage()));
        }
    }

    @Override
    public boolean onSnapshotLoad(SnapshotReader reader) {
        try {
            ledgerStateMachine.restoreFromSnapshot();
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
        log.info("Became leader at term {}", term);
    }

    @Override
    public void onLeaderStop(Status status) {
        this.isLeader = false;
        this.leaderTerm.set(-1);
        log.info("Stepped down as leader: {}", status);
    }

    // onError removed — not present in SOFAJRaft 1.3.15 StateMachineAdapter
}

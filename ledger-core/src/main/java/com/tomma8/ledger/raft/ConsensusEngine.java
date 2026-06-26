package com.tomma8.ledger.raft;

import com.tomma8.ledger.domain.command.CommandResult;
import com.tomma8.ledger.domain.command.RaftCommand;
import com.tomma8.ledger.statemachine.LedgerStateMachine;

import java.util.List;

/**
 * Consensus engine abstraction over the underlying Raft implementation.
 *
 * <p>Implemented by {@code RaftNodeManager} (SOFAJRaft, production default) and
 * {@code RatisNodeManager} (Apache Ratis, POC). Selected at wiring time via the
 * {@code ledger.consensus.engine} property. Consumers (controllers, account queue)
 * depend only on this interface so neither engine's API leaks past the raft package.
 *
 * <p>All methods are engine-agnostic. The wrapped {@link LedgerStateMachine} (business
 * logic + RocksDB + snapshot) is shared unchanged across engines.
 */
public interface ConsensusEngine extends AutoCloseable {

    /**
     * Submit a command through the Raft log. Blocks until committed to a quorum and
     * applied on this node. Must be called on the leader.
     */
    CommandResult submit(RaftCommand command);

    /**
     * Submit multiple commands in one Raft log entry when possible.
     * Returns results in the same order as {@code commands}.
     */
    default List<CommandResult> submitBatch(List<RaftCommand> commands) {
        return commands.stream().map(this::submit).toList();
    }

    /** True if this node is the current Raft leader (writes only accepted here). */
    boolean isLeader();

    /** Current leader endpoint string, or "unknown" if not yet known. */
    String getLeaderEndpoint();

    /** Monotonic last-applied Raft log index on this node. */
    long getLastAppliedIndex();

    /** The engine-agnostic ledger state machine wrapped by this engine. */
    LedgerStateMachine getLedgerStateMachine();

    /** True if the underlying Raft node has started and is running. */
    boolean isRunning();

    /** Alive peer endpoints as seen by this node; empty list if unavailable. */
    List<String> getAlivePeers();

    @Override
    void close();
}

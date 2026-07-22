package com.tomma8.ledger.domain.command;

/**
 * Raft-replicated command. The deterministic Raft log index is injected
 * as a method argument by {@link com.tomma8.ledger.raft.LedgerRaftStateMachine}
 * during {@code onApply()} and threaded through the apply methods — it is the
 * only sequence guaranteed identical across all cluster nodes.
 */
public interface RaftCommand {
    String requestId();
}

package com.tomma8.ledger.domain.command;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Raft-replicated command. Carries the Raft log index injected
 * by the leader during {@code onApply()} — the only deterministic
 * sequence across all cluster nodes.
 */
public interface RaftCommand {
    String requestId();

    /** Backing store for per-command raftLogIndex (records are immutable). */
    ConcurrentHashMap<RaftCommand, Long> INDEX_STORE = new ConcurrentHashMap<>();

    /** Raft log index injected by LedgerRaftStateMachine.onApply(). */
    default long raftLogIndex() {
        return INDEX_STORE.getOrDefault(this, 0L);
    }

    default void setRaftLogIndex(long index) {
        INDEX_STORE.put(this, index);
    }
}

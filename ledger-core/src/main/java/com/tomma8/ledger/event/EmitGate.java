package com.tomma8.ledger.event;

/**
 * Gate controlling whether LedgerStateMachine and AsyncOutboxPublisher emit
 * events to Kafka. Default closed; flipped open only after:
 *   - Raft init caught up (lastAppliedIndex >= leaderCommitIndex), AND
 *   - Node is the current Raft leader.
 *
 * Why: prevents Kafka noise during node restart (bootstrap, replay, catch-up).
 * Downstream projection dedupes by eventId, so follower apply no longer
 * needs to publish.
 */
public class EmitGate {

    private volatile boolean enabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}

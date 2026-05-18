package com.ibank.ledger.raft;

/** Tracks current Raft node role — updated by RaftNodeManager or StateMachine. */
public class NodeRole {

    private volatile boolean leader;
    private volatile long term;
    private volatile String nodeId;

    public void setLeader(String nodeId, long term) {
        this.leader = true;
        this.term = term;
        this.nodeId = nodeId;
    }

    public void setFollower(String nodeId) {
        this.leader = false;
        this.term = -1;
        this.nodeId = nodeId;
    }

    public boolean isLeader() { return leader; }
    public long getTerm() { return term; }
    public String getNodeId() { return nodeId; }
}

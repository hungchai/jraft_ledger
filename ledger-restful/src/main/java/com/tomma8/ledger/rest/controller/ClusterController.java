package com.tomma8.ledger.rest.controller;

import com.alipay.sofa.jraft.entity.PeerId;
import com.tomma8.ledger.raft.NodeRole;
import com.tomma8.ledger.raft.RaftNodeManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/ledger/cluster")
public class ClusterController {

    private final NodeRole nodeRole;
    private final String nodeId;
    private final RaftNodeManager raftNodeManager;

    public ClusterController(NodeRole nodeRole, RaftNodeManager raftNodeManager) {
        this.nodeRole = nodeRole;
        this.nodeId = System.getenv().getOrDefault("NODE_ID", "standalone");
        this.raftNodeManager = raftNodeManager;
    }

    @GetMapping("/info")
    public ResponseEntity<?> clusterInfo() {
        var body = new HashMap<String, Object>();
        body.put("nodeId", nodeId);
        body.put("role", nodeRole.isLeader() ? "LEADER" : "FOLLOWER");
        body.put("term", nodeRole.getTerm());
        body.put("isLeader", nodeRole.isLeader());
        var routing = new HashMap<String, Object>();
        routing.put("writes", nodeRole.isLeader() ? "This node accepts writes" : "Forward writes to leader");
        routing.put("reads", "This node serves reads (CQRS — any node)");
        routing.put("writeEndpoints", List.of("POST /ledger/postings", "POST /ledger/journals/{id}/reversal", "POST /ledger/adjustments/drafts/{id}/approve"));
        routing.put("readEndpoints", List.of("GET /ledger/balances", "GET /ledger/journals", "GET /health"));
        body.put("routing", routing);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/nodes")
    public ResponseEntity<?> nodes() {
        String peers = System.getenv("PEER_NODES");
        List<String> peerList = peers != null
                ? Arrays.asList(peers.split(","))
                : List.of(nodeId + ":8080");

        var body = new HashMap<String, Object>();
        body.put("currentNode", nodeId);
        body.put("allNodes", peerList);
        body.put("currentLeader", nodeRole.isLeader() ? nodeId : "unknown — query /health on each node");
        body.put("cqrs", "Reads from ANY node. Writes ONLY to leader.");
        return ResponseEntity.ok(body);
    }

    @GetMapping("/raft-status")
    public ResponseEntity<?> raftStatus() {
        if (raftNodeManager == null || raftNodeManager.getNode() == null) {
            var body = new HashMap<String, Object>();
            body.put("mode", "standalone");
            return ResponseEntity.ok(body);
        }

        long appliedIndex = raftNodeManager.getStateMachine().getLastAppliedIndex();
        long smRaftLogIndex = raftNodeManager.getStateMachine().getLedgerStateMachine().getRaftLogIndex();
        long smJournalSeq = raftNodeManager.getStateMachine().getLedgerStateMachine().getJournalSequence();

        Map<String, Object> status = new HashMap<>();
        status.put("nodeId", nodeId);
        status.put("isLeader", raftNodeManager.isLeader());
        status.put("term", nodeRole.getTerm());
        status.put("lastAppliedIndex", appliedIndex);
        status.put("smRaftLogIndex", smRaftLogIndex);
        status.put("smJournalSeq", smJournalSeq);

        String peersEnv = System.getenv("PEER_NODES");
        List<String> peers = peersEnv != null
                ? Arrays.asList(peersEnv.split(","))
                : List.of(nodeId + ":28080");
        status.put("peers", peers);

        try {
            List<String> alivePeers = raftNodeManager.getNode().listAlivePeers().stream()
                    .map(PeerId::toString)
                    .toList();
            status.put("alivePeers", alivePeers);
        } catch (Exception e) {
            status.put("alivePeers", List.of());
        }

        return ResponseEntity.ok(status);
    }
}

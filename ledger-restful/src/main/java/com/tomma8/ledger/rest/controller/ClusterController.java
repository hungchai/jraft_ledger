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
        return ResponseEntity.ok(Map.of(
                "nodeId", nodeId,
                "role", nodeRole.isLeader() ? "LEADER" : "FOLLOWER",
                "term", nodeRole.getTerm(),
                "isLeader", nodeRole.isLeader(),
                "routing", Map.of(
                        "writes", nodeRole.isLeader() ? "This node accepts writes" : "Forward writes to leader",
                        "reads", "This node serves reads (CQRS — any node)",
                        "writeEndpoints", List.of("POST /ledger/postings", "POST /ledger/journals/{id}/reversal", "POST /ledger/adjustments/drafts/{id}/approve"),
                        "readEndpoints", List.of("GET /ledger/balances", "GET /ledger/journals", "GET /health")
                )
        ));
    }

    @GetMapping("/nodes")
    public ResponseEntity<?> nodes() {
        String peers = System.getenv("PEER_NODES");
        List<String> peerList = peers != null
                ? Arrays.asList(peers.split(","))
                : List.of(nodeId + ":8080");

        return ResponseEntity.ok(Map.of(
                "currentNode", nodeId,
                "allNodes", peerList,
                "currentLeader", nodeRole.isLeader() ? nodeId : "unknown — query /health on each node",
                "cqrs", "Reads from ANY node. Writes ONLY to leader."
        ));
    }

    @GetMapping("/raft-status")
    public ResponseEntity<?> raftStatus() {
        if (raftNodeManager == null || raftNodeManager.getNode() == null) {
            return ResponseEntity.ok(Map.of("mode", "standalone"));
        }

        long appliedIndex = raftNodeManager.getStateMachine().getLastAppliedIndex();

        Map<String, Object> status = new HashMap<>();
        status.put("nodeId", nodeId);
        status.put("isLeader", raftNodeManager.isLeader());
        status.put("term", nodeRole.getTerm());
        status.put("lastAppliedIndex", appliedIndex);

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

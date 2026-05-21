package com.tomma8.ledger.rest.controller;

import com.tomma8.ledger.raft.NodeRole;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/ledger/cluster")
public class ClusterController {

    private final NodeRole nodeRole;
    private final String nodeId;

    public ClusterController(NodeRole nodeRole) {
        this.nodeRole = nodeRole;
        this.nodeId = System.getenv().getOrDefault("NODE_ID", "standalone");
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
}

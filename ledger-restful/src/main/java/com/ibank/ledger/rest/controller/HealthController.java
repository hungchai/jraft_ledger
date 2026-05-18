package com.ibank.ledger.rest.controller;

import com.ibank.ledger.raft.NodeRole;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    private final NodeRole nodeRole;

    public HealthController(NodeRole nodeRole) {
        this.nodeRole = nodeRole;
    }

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "ledger-platform",
                "nodeId", nodeRole.getNodeId() != null ? nodeRole.getNodeId() : "standalone",
                "role", nodeRole.isLeader() ? "LEADER" : "FOLLOWER",
                "term", nodeRole.getTerm()
        ));
    }
}

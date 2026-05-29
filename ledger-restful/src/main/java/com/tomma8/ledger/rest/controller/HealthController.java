package com.tomma8.ledger.rest.controller;

import com.tomma8.ledger.raft.NodeRole;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class HealthController {

    private final NodeRole nodeRole;

    public HealthController(NodeRole nodeRole) {
        this.nodeRole = nodeRole;
    }

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        var body = new HashMap<String, Object>();
        body.put("status", "UP");
        body.put("service", "ledger-platform");
        body.put("nodeId", nodeRole.getNodeId() != null ? nodeRole.getNodeId() : "standalone");
        body.put("role", nodeRole.isLeader() ? "LEADER" : "FOLLOWER");
        body.put("term", nodeRole.getTerm());
        return ResponseEntity.ok(body);
    }
}

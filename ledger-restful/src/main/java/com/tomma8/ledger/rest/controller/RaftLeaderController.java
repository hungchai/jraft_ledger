package com.tomma8.ledger.rest.controller;

import com.tomma8.ledger.raft.RaftNodeManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/raft")
public class RaftLeaderController {

    private final RaftNodeManager raftNodeManager;
    private final String nodeId;

    public RaftLeaderController(@org.springframework.beans.factory.annotation.Autowired(required = false) RaftNodeManager raftNodeManager) {
        this.raftNodeManager = raftNodeManager;
        this.nodeId = System.getenv().getOrDefault("NODE_ID", "standalone");
    }

    @GetMapping("/leader")
    public ResponseEntity<?> leader() {
        if (raftNodeManager == null || !raftNodeManager.isLeader()) {
            return ResponseEntity.status(503).body(Map.of(
                    "errorCode", "NOT_LEADER",
                    "message", "not the leader",
                    "leaderHint", raftNodeManager != null ? raftNodeManager.getLeaderEndpoint() : "unknown"
            ));
        }
        String advertiseUrl = System.getenv("LEDGER_ADVERTISE_URL");
        if (advertiseUrl == null || advertiseUrl.isBlank()) {
            String host = System.getenv().getOrDefault("HOSTNAME", nodeId);
            int port = Integer.parseInt(System.getenv().getOrDefault("SERVER_PORT", "8080"));
            advertiseUrl = "http://" + host + ":" + port;
        }
        return ResponseEntity.ok(Map.of("leader", advertiseUrl));
    }
}

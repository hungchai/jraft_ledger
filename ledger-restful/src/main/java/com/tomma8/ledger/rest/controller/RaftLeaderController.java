package com.tomma8.ledger.rest.controller;

import com.tomma8.ledger.domain.model.LedgerErrorCode;
import com.tomma8.ledger.raft.RaftNodeManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
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
            var body = new HashMap<String, Object>();
            body.put("errorCode", LedgerErrorCode.NOT_LEADER.name());
            body.put("message", "not the leader");
            body.put("leaderHint", raftNodeManager != null ? raftNodeManager.getLeaderEndpoint() : "unknown");
            return ResponseEntity.status(503).body(body);
        }
        String advertiseUrl = System.getenv("LEDGER_ADVERTISE_URL");
        if (advertiseUrl == null || advertiseUrl.isBlank()) {
            String host = System.getenv().getOrDefault("HOSTNAME", nodeId);
            int port = Integer.parseInt(System.getenv().getOrDefault("SERVER_PORT", "8080"));
            advertiseUrl = "http://" + host + ":" + port;
        }
        var body = new HashMap<String, Object>();
        body.put("leader", advertiseUrl);
        return ResponseEntity.ok(body);
    }
}

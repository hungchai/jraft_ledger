package com.tomma8.ledger.rest.controller;

import com.tomma8.ledger.domain.model.LedgerErrorCode;
import com.tomma8.ledger.raft.ConsensusEngine;
import com.tomma8.ledger.rest.config.properties.LedgerProperties;
import com.tomma8.ledger.rest.config.properties.ServerPortProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;

@RestController
@RequestMapping("/raft")
public class RaftLeaderController {

    private final ConsensusEngine raftNodeManager;
    private final String nodeId;
    private final LedgerProperties ledgerProps;
    private final int serverPort;

    public RaftLeaderController(
            @org.springframework.beans.factory.annotation.Autowired(required = false) ConsensusEngine raftNodeManager,
            LedgerProperties ledgerProps,
            ServerPortProperties serverPortProps) {
        this.raftNodeManager = raftNodeManager;
        this.ledgerProps = ledgerProps;
        String id = ledgerProps.getNode().getId();
        this.nodeId = (id != null && !id.isBlank()) ? id : "standalone";
        this.serverPort = serverPortProps.getPort();
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
        String advertiseUrl = ledgerProps.getAdvertiseUrl();
        if (advertiseUrl == null || advertiseUrl.isBlank()) {
            String host = ledgerProps.getNode().getHostname();
            if (host == null || host.isBlank()) {
                host = nodeId;
            }
            advertiseUrl = "http://" + host + ":" + serverPort;
        }
        var body = new HashMap<String, Object>();
        body.put("leader", advertiseUrl);
        return ResponseEntity.ok(body);
    }
}

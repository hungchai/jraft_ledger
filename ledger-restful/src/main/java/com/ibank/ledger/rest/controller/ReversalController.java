package com.ibank.ledger.rest.controller;

import com.ibank.ledger.domain.command.CommandResult;
import com.ibank.ledger.domain.command.ReversalCommand;
import com.ibank.ledger.raft.NodeRole;
import com.ibank.ledger.raft.RaftNodeManager;
import com.ibank.ledger.service.ReversalService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/ledger/journals")
public class ReversalController {

    private final ReversalService reversalService;
    private final RaftNodeManager raftNodeManager;
    private final NodeRole nodeRole;

    public ReversalController(ReversalService reversalService,
                               @org.springframework.beans.factory.annotation.Autowired(required = false) RaftNodeManager raftNodeManager,
                               NodeRole nodeRole) {
        this.reversalService = reversalService;
        this.raftNodeManager = raftNodeManager;
        this.nodeRole = nodeRole;
    }

    @PostMapping("/{journalId}/reversal")
    public ResponseEntity<?> reverse(@PathVariable String journalId, @RequestBody Map<String, String> body) {
        if (!nodeRole.isLeader()) {
            return ResponseEntity.status(503).body(Map.of(
                    "status", "REJECTED",
                    "errorCodes", List.of("NOT_LEADER"),
                    "leaderHint", "Query /health on each node to find the leader"
            ));
        }
        ReversalCommand cmd = new ReversalCommand(
                body.get("requestId"),
                journalId,
                body.get("reversalReason"),
                body.get("reversalReasonCode"),
                LocalDate.parse(body.get("valueDate")));

        CommandResult result;
        if (raftNodeManager != null) {
            result = raftNodeManager.submit(cmd);
        } else {
            result = reversalService.reverse(cmd);
        }

        if (result.isRejected()) {
            return ResponseEntity.badRequest().body(result);
        }
        return ResponseEntity.ok(result);
    }
}

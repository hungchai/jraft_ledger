package com.tomma8.ledger.rest.controller;

import com.tomma8.ledger.domain.command.CommandResult;
import com.tomma8.ledger.domain.command.ReversalCommand;
import com.tomma8.ledger.raft.NodeRole;
import com.tomma8.ledger.raft.RaftNodeManager;
import com.tomma8.ledger.service.ReversalService;
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
            String leader = raftNodeManager != null ? raftNodeManager.getLeaderEndpoint() : "unknown";
            return ResponseEntity.status(503).body(Map.of(
                    "status", "REJECTED",
                    "errorCodes", List.of("NOT_LEADER"),
                    "leaderHint", leader
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

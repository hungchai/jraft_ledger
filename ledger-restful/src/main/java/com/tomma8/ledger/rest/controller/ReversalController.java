package com.tomma8.ledger.rest.controller;

import com.tomma8.ledger.domain.command.CommandResult;
import com.tomma8.ledger.domain.command.ReversalCommand;
import com.tomma8.ledger.raft.NodeRole;
import com.tomma8.ledger.raft.RaftNodeManager;
import com.tomma8.ledger.service.ReversalService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/ledger/journals")
public class ReversalController {

    private final ReversalService reversalService;
    private final RaftNodeManager raftNodeManager;
    private final NodeRole nodeRole;
    private final MeterRegistry meterRegistry;

    public ReversalController(ReversalService reversalService,
                               @org.springframework.beans.factory.annotation.Autowired(required = false) RaftNodeManager raftNodeManager,
                               NodeRole nodeRole,
                               MeterRegistry meterRegistry) {
        this.reversalService = reversalService;
        this.raftNodeManager = raftNodeManager;
        this.nodeRole = nodeRole;
        this.meterRegistry = meterRegistry;
    }

    @PostMapping("/{journalId}/reversal")
    public ResponseEntity<?> reverse(@PathVariable String journalId, @RequestBody Map<String, String> body) {
        long start = System.nanoTime();
        String outcome = "COMPLETED";
        try {
            if (!nodeRole.isLeader()) {
                outcome = "REJECTED";
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
                outcome = "REJECTED";
                return ResponseEntity.badRequest().body(result);
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            outcome = "ERROR";
            throw e;
        } finally {
            meterRegistry.timer("ledger.reversal.duration", "outcome", outcome)
                    .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }
}

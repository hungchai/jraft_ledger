package com.tomma8.ledger.rest.controller;

import com.tomma8.ledger.domain.command.CommandResult;
import com.tomma8.ledger.domain.command.ReversalCommand;
import com.tomma8.ledger.domain.model.LedgerErrorCode;
import com.tomma8.ledger.queue.AccountQueueManager;
import com.tomma8.ledger.raft.NodeRole;
import com.tomma8.ledger.raft.ConsensusEngine;
import com.tomma8.ledger.service.ReversalService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/ledger/journals")
public class ReversalController {

    private final ReversalService reversalService;
    private final ConsensusEngine raftNodeManager;
    private final AccountQueueManager accountQueueManager;
    private final NodeRole nodeRole;
    private final MeterRegistry meterRegistry;

    public ReversalController(ReversalService reversalService,
                               @org.springframework.beans.factory.annotation.Autowired(required = false) ConsensusEngine raftNodeManager,
                               @org.springframework.beans.factory.annotation.Autowired(required = false) AccountQueueManager accountQueueManager,
                               NodeRole nodeRole,
                               MeterRegistry meterRegistry) {
        this.reversalService = reversalService;
        this.raftNodeManager = raftNodeManager;
        this.accountQueueManager = accountQueueManager;
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
                var responseBody = new HashMap<String, Object>();
                responseBody.put("status", "REJECTED");
                responseBody.put("errorCodes", List.of(LedgerErrorCode.NOT_LEADER.name()));
                responseBody.put("leaderHint", leader);
                return ResponseEntity.status(503).body(responseBody);
            }
            ReversalCommand cmd = new ReversalCommand(
                    body.get("requestId"),
                    journalId,
                    body.get("reversalReason"),
                    body.get("reversalReasonCode"),
                    LocalDate.parse(body.get("valueDate")));

            CommandResult result;
            if (accountQueueManager != null) {
                // Use originalJournalId as queue anchor — reversals for same journal always touch same accounts
                try {
                    result = accountQueueManager.submitAsync(journalId, cmd).get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException("AccountQueue submit failed for journal " + journalId, e);
                }
            } else if (raftNodeManager != null) {
                result = raftNodeManager.submit(cmd);
            } else {
                result = reversalService.reverse(cmd);
            }

            if (result.isRejected()) {
                outcome = "REJECTED";
                return ResponseEntity.badRequest().body(toResponseMap(result));
            }
            return ResponseEntity.ok(toResponseMap(result));
        } catch (Exception e) {
            outcome = "ERROR";
            throw e;
        } finally {
            meterRegistry.timer("ledger.reversal.duration", "outcome", outcome)
                    .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    private Map<String, Object> toResponseMap(CommandResult result) {
        var map = new HashMap<String, Object>();
        map.put("status", result.status());
        map.put("journalId", result.journalId() != null ? result.journalId() : "");
        map.put("errorCodes", result.errorCodes().stream().map(LedgerErrorCode::name).toList());
        map.put("errorDetails", result.errorDetails());
        return map;
    }
}

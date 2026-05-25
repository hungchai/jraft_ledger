package com.tomma8.ledger.rest.controller;

import com.tomma8.ledger.domain.command.AdjustmentCommand;
import com.tomma8.ledger.domain.command.CommandResult;
import com.tomma8.ledger.domain.command.PostingCommand;
import com.tomma8.ledger.domain.model.AdjustmentDraft;
import com.tomma8.ledger.domain.model.EntryType;
import com.tomma8.ledger.domain.model.LedgerErrorCode;
import com.tomma8.ledger.raft.NodeRole;
import com.tomma8.ledger.raft.RaftNodeManager;
import com.tomma8.ledger.service.AdjustmentService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/ledger/adjustments")
public class AdjustmentController {

    private final AdjustmentService adjustmentService;
    private final RaftNodeManager raftNodeManager;
    private final NodeRole nodeRole;
    private final MeterRegistry meterRegistry;

    public AdjustmentController(AdjustmentService adjustmentService,
                                 @org.springframework.beans.factory.annotation.Autowired(required = false) RaftNodeManager raftNodeManager,
                                 NodeRole nodeRole,
                                 MeterRegistry meterRegistry) {
        this.adjustmentService = adjustmentService;
        this.raftNodeManager = raftNodeManager;
        this.nodeRole = nodeRole;
        this.meterRegistry = meterRegistry;
    }

    @PostMapping("/drafts")
    public ResponseEntity<?> createDraft(@RequestBody Map<String, Object> body) {
        long start = System.nanoTime();
        String outcome = "COMPLETED";
        try {
            String requestId = (String) body.get("requestId");
            String makerId = (String) body.get("makerId");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> legMaps = (List<Map<String, Object>>) body.get("legs");
            List<PostingCommand.Leg> legs = parseLegs(legMaps);

            PostingCommand cmd = new PostingCommand(requestId, "MANUAL_ADJUSTMENT",
                    "ADJ-" + requestId, LocalDate.now(), legs);

            try {
                AdjustmentDraft draft = adjustmentService.createDraft(cmd, makerId);
                return ResponseEntity.ok(draft);
            } catch (IllegalArgumentException e) {
                outcome = "REJECTED";
                return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
            }
        } catch (Exception e) {
            outcome = "ERROR";
            throw e;
        } finally {
            meterRegistry.timer("ledger.adjustment.duration",
                            "outcome", outcome,
                            "operation", "create-draft")
                    .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    @PostMapping("/drafts/{draftId}/approve")
    public ResponseEntity<?> approve(@PathVariable String draftId, @RequestBody Map<String, String> body) {
        long start = System.nanoTime();
        String outcome = "COMPLETED";
        try {
            if (!nodeRole.isLeader()) {
                outcome = "REJECTED";
                String leader = raftNodeManager != null ? raftNodeManager.getLeaderEndpoint() : "unknown";
                return ResponseEntity.status(503).body(Map.of(
                        "status", "REJECTED",
                        "errorCodes", List.of(LedgerErrorCode.NOT_LEADER.name()),
                        "leaderHint", leader
                ));
            }

            String checkerId = body.get("checkerId");
            String approveRequestId = body.get("approveRequestId");

            PostingCommand postingCmd = adjustmentService.validateDraftForApproval(draftId, checkerId);
            AdjustmentCommand adjCmd = new AdjustmentCommand(
                    postingCmd.requestId(), postingCmd.businessEventType(), postingCmd.businessEventRef(),
                    postingCmd.valueDate(), postingCmd.legs(), "MANUAL_ADJUSTMENT", draftId);

            CommandResult result;
            if (raftNodeManager != null) {
                result = raftNodeManager.submit(adjCmd);
            } else {
                result = adjustmentService.approveDraft(draftId, checkerId, approveRequestId);
                adjustmentService.recordApproveResult(draftId, approveRequestId, result);
                return ResponseEntity.ok(toResponseMap(result));
            }

            adjustmentService.recordApproveResult(draftId, approveRequestId, result);

            if (result.isRejected()) {
                outcome = "REJECTED";
                return ResponseEntity.badRequest().body(toResponseMap(result));
            }
            return ResponseEntity.ok(toResponseMap(result));
        } catch (Exception e) {
            outcome = "ERROR";
            throw e;
        } finally {
            meterRegistry.timer("ledger.adjustment.duration",
                            "outcome", outcome,
                            "operation", "approve")
                    .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    @PostMapping("/drafts/{draftId}/reject")
    public ResponseEntity<?> reject(@PathVariable String draftId, @RequestBody Map<String, String> body) {
        adjustmentService.rejectDraft(draftId, body.get("checkerId"),
                body.getOrDefault("reason", "Rejected"));
        return ResponseEntity.ok(Map.of("status", "REJECTED"));
    }

    private List<PostingCommand.Leg> parseLegs(List<Map<String, Object>> legMaps) {
        return legMaps.stream().map(legMap -> {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> lineMaps = (List<Map<String, Object>>) legMap.get("lines");
            List<PostingCommand.Line> lines = lineMaps.stream().map(lineMap ->
                    new PostingCommand.Line(
                            (String) lineMap.get("accountId"),
                            (String) lineMap.get("balanceType"),
                            (String) lineMap.getOrDefault("position", "CURRENT"),
                            EntryType.valueOf((String) lineMap.get("entryType")),
                            (String) lineMap.getOrDefault("description", ""))
            ).toList();
            return new PostingCommand.Leg(
                    (String) legMap.get("legId"),
                    (String) legMap.get("postingType"),
                    new BigDecimal(legMap.get("amount").toString()),
                    (String) legMap.get("currency"),
                    lines);
        }).toList();
    }

    private Map<String, Object> toResponseMap(CommandResult result) {
        return Map.of(
                "status", result.status(),
                "journalId", result.journalId() != null ? result.journalId() : "",
                "errorCodes", result.errorCodes().stream().map(LedgerErrorCode::name).toList()
        );
    }
}

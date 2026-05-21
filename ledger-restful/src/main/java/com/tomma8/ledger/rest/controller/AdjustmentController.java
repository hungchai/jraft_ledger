package com.tomma8.ledger.rest.controller;

import com.tomma8.ledger.domain.command.CommandResult;
import com.tomma8.ledger.domain.command.PostingCommand;
import com.tomma8.ledger.domain.model.AdjustmentDraft;
import com.tomma8.ledger.domain.model.EntryType;
import com.tomma8.ledger.raft.NodeRole;
import com.tomma8.ledger.raft.RaftNodeManager;
import com.tomma8.ledger.service.AdjustmentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/ledger/adjustments")
public class AdjustmentController {

    private final AdjustmentService adjustmentService;
    private final RaftNodeManager raftNodeManager;
    private final NodeRole nodeRole;

    public AdjustmentController(AdjustmentService adjustmentService,
                                 @org.springframework.beans.factory.annotation.Autowired(required = false) RaftNodeManager raftNodeManager,
                                 NodeRole nodeRole) {
        this.adjustmentService = adjustmentService;
        this.raftNodeManager = raftNodeManager;
        this.nodeRole = nodeRole;
    }

    @PostMapping("/drafts")
    public ResponseEntity<?> createDraft(@RequestBody Map<String, Object> body) {
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
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/drafts/{draftId}/approve")
    public ResponseEntity<?> approve(@PathVariable String draftId, @RequestBody Map<String, String> body) {
        if (!nodeRole.isLeader()) {
            return ResponseEntity.status(503).body(Map.of(
                    "status", "REJECTED",
                    "errorCodes", List.of("NOT_LEADER"),
                    "leaderHint", "Query /health on each node to find the leader"
            ));
        }

        String checkerId = body.get("checkerId");
        String approveRequestId = body.get("approveRequestId");

        // Local validation (draft existence, maker-checker, expiry, status)
        PostingCommand cmd = adjustmentService.validateDraftForApproval(draftId, checkerId);

        // Execute via Raft (or direct in standalone mode)
        CommandResult result;
        if (raftNodeManager != null) {
            result = raftNodeManager.submit(cmd);
        } else {
            result = adjustmentService.approveDraft(draftId, checkerId, approveRequestId);
            // approveDraft already records the result; avoid double record
            return ResponseEntity.ok(result);
        }

        adjustmentService.recordApproveResult(draftId, approveRequestId, result);
        return ResponseEntity.ok(result);
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
                            (String) lineMap.get("currency"),
                            EntryType.valueOf((String) lineMap.get("entryType")),
                            new BigDecimal(lineMap.get("amount").toString()),
                            (String) lineMap.getOrDefault("description", ""))
            ).toList();
            return new PostingCommand.Leg(
                    (String) legMap.get("legId"),
                    (String) legMap.get("postingType"),
                    lines);
        }).toList();
    }
}

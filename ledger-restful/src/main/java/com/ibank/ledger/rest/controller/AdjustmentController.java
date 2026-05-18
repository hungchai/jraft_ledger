package com.ibank.ledger.rest.controller;

import com.ibank.ledger.domain.command.CommandResult;
import com.ibank.ledger.domain.command.PostingCommand;
import com.ibank.ledger.domain.model.AdjustmentDraft;
import com.ibank.ledger.domain.model.EntryType;
import com.ibank.ledger.service.AdjustmentService;
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

    public AdjustmentController(AdjustmentService adjustmentService) {
        this.adjustmentService = adjustmentService;
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
        CommandResult result = adjustmentService.approveDraft(
                draftId, body.get("checkerId"), body.get("approveRequestId"));
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

package com.ibank.ledger.rest.controller;

import com.ibank.ledger.domain.command.CommandResult;
import com.ibank.ledger.domain.command.ReversalCommand;
import com.ibank.ledger.service.ReversalService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/ledger/journals")
public class ReversalController {

    private final ReversalService reversalService;

    public ReversalController(ReversalService reversalService) {
        this.reversalService = reversalService;
    }

    @PostMapping("/{journalId}/reversal")
    public ResponseEntity<?> reverse(@PathVariable String journalId, @RequestBody Map<String, String> body) {
        ReversalCommand cmd = new ReversalCommand(
                body.get("requestId"),
                journalId,
                body.get("reversalReason"),
                body.get("reversalReasonCode"),
                LocalDate.parse(body.get("valueDate")));
        CommandResult result = reversalService.reverse(cmd);

        if (result.isRejected()) {
            return ResponseEntity.badRequest().body(result);
        }
        return ResponseEntity.ok(result);
    }
}

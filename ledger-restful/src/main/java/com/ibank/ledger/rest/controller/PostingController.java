package com.ibank.ledger.rest.controller;

import com.ibank.ledger.domain.command.CommandResult;
import com.ibank.ledger.domain.command.PostingCommand;
import com.ibank.ledger.domain.model.EntryType;
import com.ibank.ledger.service.PostingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/ledger/postings")
public class PostingController {

    private final PostingService postingService;

    public PostingController(PostingService postingService) {
        this.postingService = postingService;
    }

    @PostMapping
    public ResponseEntity<?> post(@RequestBody Map<String, Object> body) {
        String requestId = (String) body.get("requestId");
        String businessEventType = (String) body.get("businessEventType");
        String businessEventRef = (String) body.get("businessEventRef");
        LocalDate valueDate = LocalDate.parse((String) body.get("valueDate"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> legMaps = (List<Map<String, Object>>) body.get("legs");
        List<PostingCommand.Leg> legs = legMaps.stream().map(legMap -> {
            String legId = (String) legMap.get("legId");
            String postingType = (String) legMap.get("postingType");
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
            return new PostingCommand.Leg(legId, postingType, lines);
        }).toList();

        PostingCommand cmd = new PostingCommand(requestId, businessEventType, businessEventRef, valueDate, legs);
        CommandResult result = postingService.post(cmd);

        if (result.isRejected()) {
            return ResponseEntity.badRequest().body(result);
        }
        return ResponseEntity.ok(result);
    }
}

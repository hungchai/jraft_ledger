package com.tomma8.ledger.rest.controller;

import com.tomma8.ledger.domain.command.CommandResult;
import com.tomma8.ledger.domain.command.PostingCommand;
import com.tomma8.ledger.domain.model.EntryType;
import com.tomma8.ledger.raft.NodeRole;
import com.tomma8.ledger.raft.RaftNodeManager;
import com.tomma8.ledger.service.PostingService;
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
    private final RaftNodeManager raftNodeManager;
    private final NodeRole nodeRole;

    public PostingController(PostingService postingService,
                              @org.springframework.beans.factory.annotation.Autowired(required = false) RaftNodeManager raftNodeManager,
                              NodeRole nodeRole) {
        this.postingService = postingService;
        this.raftNodeManager = raftNodeManager;
        this.nodeRole = nodeRole;
    }

    @PostMapping
    public ResponseEntity<?> post(@RequestBody Map<String, Object> body) {
        if (!nodeRole.isLeader()) {
            return ResponseEntity.status(503).body(Map.of(
                    "status", "REJECTED",
                    "errorCodes", List.of("NOT_LEADER"),
                    "leaderHint", "Query /health on each node to find the leader"
            ));
        }
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

        // Route through Raft for replication to followers
        CommandResult result;
        if (raftNodeManager != null) {
            result = raftNodeManager.submit(cmd);
        } else {
            result = postingService.post(cmd);
        }

        if (result.isRejected()) {
            return ResponseEntity.badRequest().body(result);
        }
        return ResponseEntity.ok(result);
    }
}

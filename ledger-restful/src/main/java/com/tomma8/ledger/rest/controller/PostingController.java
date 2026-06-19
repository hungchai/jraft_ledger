package com.tomma8.ledger.rest.controller;

import com.tomma8.ledger.config.ConfigService;
import com.tomma8.ledger.domain.command.CommandResult;
import com.tomma8.ledger.domain.command.PostingCommand;
import com.tomma8.ledger.domain.model.EntryType;
import com.tomma8.ledger.domain.model.LedgerErrorCode;
import com.tomma8.ledger.queue.AccountQueueManager;
import com.tomma8.ledger.queue.CommandQueueManager;
import com.tomma8.ledger.raft.NodeRole;
import com.tomma8.ledger.raft.ConsensusEngine;
import com.tomma8.ledger.service.PostingService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/ledger/postings")
public class PostingController {

    private final PostingService postingService;
    private final ConsensusEngine raftNodeManager;
    private final CommandQueueManager commandQueueManager;
    private final AccountQueueManager accountQueueManager;
    private final NodeRole nodeRole;
    private final MeterRegistry meterRegistry;

    // Admission control: cap concurrent in-flight postings so an overload burst
    // sheds load (503) instead of accumulating in-flight work on the heap until
    // OOM. Bounds the in-flight working set; does NOT touch consensus.
    private final Semaphore inflight;
    private final int acquireTimeoutMs;

    public PostingController(PostingService postingService,
                              @org.springframework.beans.factory.annotation.Autowired(required = false) ConsensusEngine raftNodeManager,
                              @org.springframework.beans.factory.annotation.Autowired(required = false) CommandQueueManager commandQueueManager,
                              @org.springframework.beans.factory.annotation.Autowired(required = false) AccountQueueManager accountQueueManager,
                              NodeRole nodeRole,
                              MeterRegistry meterRegistry,
                              ConfigService cs) {
        this.postingService = postingService;
        this.raftNodeManager = raftNodeManager;
        this.commandQueueManager = commandQueueManager;
        this.accountQueueManager = accountQueueManager;
        this.nodeRole = nodeRole;
        this.meterRegistry = meterRegistry;
        int maxInflight = cs.getInt("LEDGER_MAX_INFLIGHT_POSTINGS", 256);
        this.acquireTimeoutMs = cs.getInt("LEDGER_INFLIGHT_ACQUIRE_MS", 100);
        this.inflight = new Semaphore(maxInflight, true);
        Gauge.builder("ledger.posting.inflight.available", inflight::availablePermits)
                .description("Available posting admission-control permits (0 = saturated, shedding)")
                .register(meterRegistry);
    }

    @PostMapping
    public ResponseEntity<?> post(@RequestBody Map<String, Object> body) {
        long start = System.nanoTime();
        String outcome = "COMPLETED";
        String businessEventType = (String) body.get("businessEventType");
        try {
            if (!nodeRole.isLeader()) {
                outcome = "REJECTED";
                String leader = raftNodeManager != null ? raftNodeManager.getLeaderEndpoint() : "unknown";
                meterRegistry.counter("ledger.posting.rejected.count", "errorCode", LedgerErrorCode.NOT_LEADER.name()).increment();
                var responseBody = new HashMap<String, Object>();
                responseBody.put("status", "REJECTED");
                responseBody.put("errorCodes", List.of(LedgerErrorCode.NOT_LEADER.name()));
                responseBody.put("leaderHint", leader);
                return ResponseEntity.status(503).body(responseBody);
            }

            // Admission control — shed load when in-flight postings are saturated.
            boolean acquired;
            try {
                acquired = inflight.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                acquired = false;
            }
            if (!acquired) {
                outcome = "REJECTED";
                meterRegistry.counter("ledger.posting.rejected.count", "errorCode", LedgerErrorCode.QUEUE_FULL.name()).increment();
                var busy = new HashMap<String, Object>();
                busy.put("status", "REJECTED");
                busy.put("errorCodes", List.of(LedgerErrorCode.QUEUE_FULL.name()));
                busy.put("reason", "in-flight posting limit reached — retry");
                return ResponseEntity.status(503).header("Retry-After", "1").body(busy);
            }
            try {
            String requestId = (String) body.get("requestId");
            String businessEventRef = (String) body.get("businessEventRef");
            LocalDate valueDate = LocalDate.parse((String) body.get("valueDate"));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> legMaps = (List<Map<String, Object>>) body.get("legs");
            List<PostingCommand.Leg> legs = legMaps.stream().map(legMap -> {
                String legId = (String) legMap.get("legId");
                String postingType = (String) legMap.get("postingType");
                BigDecimal amount = new BigDecimal(legMap.get("amount").toString());
                String currency = (String) legMap.get("currency");
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
                return new PostingCommand.Leg(legId, postingType, amount, currency, lines);
            }).toList();

            PostingCommand cmd = new PostingCommand(requestId, businessEventType, businessEventRef, valueDate, legs);

            CommandResult result;
            if (commandQueueManager != null) {
                try {
                    result = commandQueueManager.submitAsync(cmd).get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException("CommandQueue submit failed for " + cmd.requestId(), e);
                }
            } else if (accountQueueManager != null) {
                // Extract all accountIds from posting, sort to get deterministic queue anchor (deadlock prevention)
                String anchorAccount = cmd.legs().stream()
                        .flatMap(leg -> leg.lines().stream())
                        .map(PostingCommand.Line::accountId)
                        .sorted()
                        .findFirst()
                        .orElseThrow();
                try {
                    result = accountQueueManager.submitAsync(anchorAccount, cmd).get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException("AccountQueue submit failed for " + anchorAccount, e);
                }
            } else if (raftNodeManager != null) {
                result = raftNodeManager.submit(cmd);
            } else {
                result = postingService.post(cmd);
            }

            if (result.isRejected()) {
                outcome = "REJECTED";
                LedgerErrorCode errorCode = result.errorCodes().isEmpty() ? LedgerErrorCode.INVALID_REQUEST_ID : result.errorCodes().get(0);
                meterRegistry.counter("ledger.posting.rejected.count", "errorCode", errorCode.name()).increment();
                return ResponseEntity.badRequest().body(toResponseMap(result));
            }
            return ResponseEntity.ok(toResponseMap(result));
            } finally {
                inflight.release();
            }
        } catch (Exception e) {
            outcome = "ERROR";
            throw e;
        } finally {
            meterRegistry.timer("ledger.posting.duration",
                            "outcome", outcome,
                            "businessEventType", businessEventType != null ? businessEventType : "UNKNOWN")
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

package com.tomma8.ledger.rest.controller;

import com.tomma8.ledger.domain.model.Journal;
import com.tomma8.ledger.domain.model.ReconciliationReport;
import com.tomma8.ledger.service.JournalQueryService;
import com.tomma8.ledger.service.ReconciliationService;
import com.tomma8.ledger.service.ReconciliationService.ExternalRecord;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/ledger/reconciliation")
public class ReconciliationController {

    private final ReconciliationService reconciliationService;
    private final JournalQueryService journalQueryService;

    public ReconciliationController(ReconciliationService reconciliationService,
                                     JournalQueryService journalQueryService) {
        this.reconciliationService = reconciliationService;
        this.journalQueryService = journalQueryService;
    }

    @PostMapping("/l1")
    public ResponseEntity<?> runL1(@RequestBody Map<String, String> body) {
        String date = body.get("date");
        // In production, this would get all journals for the date
        // For now, we don't have date-based query — stub
        return ResponseEntity.ok(Map.of("message", "L1 reconciliation endpoint ready"));
    }

    @PostMapping("/l2")
    public ResponseEntity<?> runL2(@RequestBody Map<String, Object> body) {
        String date = (String) body.get("date");
        @SuppressWarnings("unchecked")
        Map<String, Object> accountBalances = (Map<String, Object>) body.get("accountBalances");
        String controlId = (String) body.get("controlAccountId");
        BigDecimal controlBalance = new BigDecimal(body.get("controlBalance").toString());
        BigDecimal tolerance = new BigDecimal(body.getOrDefault("tolerance", "0.01").toString());

        // Convert to string-keyed map
        Map<String, BigDecimal> balances = new java.util.HashMap<>();
        accountBalances.forEach((k, v) -> {
            if (v instanceof Number n) balances.put(k, new BigDecimal(n.toString()));
            else balances.put(k, new BigDecimal(v.toString()));
        });

        ReconciliationReport report = reconciliationService.runL2Reconciliation(
                date, balances, controlId, controlBalance, tolerance);
        return ResponseEntity.ok(report);
    }

    @PostMapping("/l3")
    public ResponseEntity<?> runL3(@RequestBody Map<String, Object> body) {
        String date = (String) body.get("date");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> extList = (List<Map<String, Object>>) body.get("externalRecords");
        @SuppressWarnings("unchecked")
        List<String> journalIds = (List<String>) body.get("journalIds");

        List<ExternalRecord> external = extList.stream()
                .map(m -> new ExternalRecord(
                        (String) m.get("externalRef"),
                        new BigDecimal(m.get("amount").toString())))
                .toList();

        List<Journal> journals = journalIds.stream()
                .map(journalQueryService::getJournal)
                .filter(j -> j != null)
                .toList();

        ReconciliationReport report = reconciliationService.runL3Reconciliation(date, external, journals);
        return ResponseEntity.ok(report);
    }
}

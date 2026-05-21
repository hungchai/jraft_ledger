package com.tomma8.ledger.rest.controller;

import com.tomma8.ledger.domain.model.Journal;
import com.tomma8.ledger.service.JournalQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ledger/journals")
public class JournalQueryController {

    private final JournalQueryService journalQueryService;

    public JournalQueryController(JournalQueryService journalQueryService) {
        this.journalQueryService = journalQueryService;
    }

    @GetMapping("/{journalId}")
    public ResponseEntity<?> getJournal(@PathVariable String journalId) {
        Journal journal = journalQueryService.getJournal(journalId);
        if (journal == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(journal);
    }

    @GetMapping
    public ResponseEntity<?> getJournalsByAccount(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(journalQueryService.getJournalsByAccount(accountId, page, size));
    }

    @GetMapping("/by-business-ref")
    public ResponseEntity<?> getJournalsByBusinessRef(@RequestParam String businessEventRef) {
        return ResponseEntity.ok(journalQueryService.getJournalsByBusinessEventRef(businessEventRef));
    }

    @GetMapping("/chain/{journalId}")
    public ResponseEntity<?> getJournalChain(@PathVariable String journalId) {
        return ResponseEntity.ok(journalQueryService.getJournalChain(journalId));
    }

    @GetMapping("/by-request-id")
    public ResponseEntity<?> getJournalByRequestId(@RequestParam String requestId) {
        Journal journal = journalQueryService.getJournalByRequestId(requestId);
        if (journal == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(journal);
    }
}

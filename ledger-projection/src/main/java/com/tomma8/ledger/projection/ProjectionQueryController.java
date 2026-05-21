package com.tomma8.ledger.projection;

import com.tomma8.ledger.dao.mapper.AccountBalanceMapper;
import com.tomma8.ledger.dao.mapper.JournalMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-path APIs that query MySQL View Layer directly instead of Raft State Machine.
 * These offload read traffic from Raft nodes and are served by the Projection Service.
 *
 * Endpoints:
 * - GET /projection/accounts/{accountId}/balances
 * - GET /projection/journals/{journalId}
 * - GET /projection/accounts/{accountId}/journals
 * - GET /projection/journals/by-request-id
 */
@RestController
@RequestMapping("/projection")
public class ProjectionQueryController {

    private static final Logger log = LoggerFactory.getLogger(ProjectionQueryController.class);

    private final AccountBalanceMapper accountBalanceMapper;
    private final JournalMapper journalMapper;

    public ProjectionQueryController(AccountBalanceMapper accountBalanceMapper,
                                     JournalMapper journalMapper) {
        this.accountBalanceMapper = accountBalanceMapper;
        this.journalMapper = journalMapper;
    }

    @GetMapping("/accounts/{accountId}/balances")
    public ResponseEntity<?> getBalances(@PathVariable String accountId,
                                         @RequestParam(required = false) String balanceType,
                                         @RequestParam(required = false) String currency) {
        try {
            List<Map<String, Object>> balances;
            if (balanceType != null && currency != null) {
                Map<String, Object> single = accountBalanceMapper.findByKey(accountId, balanceType, currency);
                balances = single != null ? List.of(single) : List.of();
            } else {
                balances = accountBalanceMapper.findByAccountId(accountId);
            }
            return ResponseEntity.ok(Map.of(
                    "accountId", accountId,
                    "dataSource", "VIEW_LAYER",
                    "balances", balances
            ));
        } catch (Exception e) {
            log.error("Failed to query balance for {}", accountId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/journals/{journalId}")
    public ResponseEntity<?> getJournal(@PathVariable String journalId) {
        try {
            Map<String, Object> journal = journalMapper.findJournalById(journalId);
            if (journal == null) {
                return ResponseEntity.notFound().build();
            }
            List<Map<String, Object>> lines = journalMapper.findLinesByJournalId(journalId);
            Map<String, Object> result = new HashMap<>(journal);
            result.put("lines", lines);
            result.put("dataSource", "VIEW_LAYER");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to query journal {}", journalId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/accounts/{accountId}/journals")
    public ResponseEntity<?> getJournalsByAccount(@PathVariable String accountId,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "50") int size) {
        try {
            int offset = page * size;
            List<Map<String, Object>> journals = journalMapper.findJournalsByAccount(accountId, offset, size);
            return ResponseEntity.ok(Map.of(
                    "accountId", accountId,
                    "page", page,
                    "size", size,
                    "items", journals,
                    "dataSource", "VIEW_LAYER"
            ));
        } catch (Exception e) {
            log.error("Failed to query journals for {}", accountId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/journals/by-request-id")
    public ResponseEntity<?> getJournalByRequestId(@RequestParam String requestId) {
        try {
            Map<String, Object> journal = journalMapper.findJournalByRequestId(requestId);
            if (journal == null) {
                return ResponseEntity.notFound().build();
            }
            List<Map<String, Object>> lines = journalMapper.findLinesByJournalId((String) journal.get("journal_id"));
            Map<String, Object> result = new HashMap<>(journal);
            result.put("lines", lines);
            result.put("dataSource", "VIEW_LAYER");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to query journal by requestId {}", requestId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}

package com.ibank.ledger.rest.controller;

import com.ibank.ledger.domain.model.AccountBalanceKey;
import com.ibank.ledger.domain.model.BalanceQueryResult;
import com.ibank.ledger.service.BalanceQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/ledger/balances")
public class BalanceQueryController {

    private final BalanceQueryService balanceQueryService;

    public BalanceQueryController(BalanceQueryService balanceQueryService) {
        this.balanceQueryService = balanceQueryService;
    }

    @GetMapping
    public ResponseEntity<?> getBalance(
            @RequestParam String accountId,
            @RequestParam String balanceType,
            @RequestParam String currency) {
        BalanceQueryResult result = balanceQueryService.getBalance(accountId, balanceType, currency);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/as-of")
    public ResponseEntity<?> getAsOfBalance(
            @RequestParam String accountId,
            @RequestParam String balanceType,
            @RequestParam String currency,
            @RequestParam String asOf) {
        BalanceQueryResult result = balanceQueryService.getAsOfBalance(
                accountId, balanceType, currency, LocalDate.parse(asOf));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/batch")
    public ResponseEntity<?> getBatchBalances(@RequestBody List<AccountBalanceKey> keys) {
        List<BalanceQueryResult> results = balanceQueryService.getBatchBalances(keys);
        return ResponseEntity.ok(results);
    }
}

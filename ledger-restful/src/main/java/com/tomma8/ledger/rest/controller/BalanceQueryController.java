package com.tomma8.ledger.rest.controller;

import com.tomma8.ledger.domain.model.AccountBalanceKey;
import com.tomma8.ledger.domain.model.BalanceQueryResult;
import com.tomma8.ledger.service.BalanceQueryService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/ledger/balances")
public class BalanceQueryController {

    private final BalanceQueryService balanceQueryService;
    private final MeterRegistry meterRegistry;

    public BalanceQueryController(BalanceQueryService balanceQueryService, MeterRegistry meterRegistry) {
        this.balanceQueryService = balanceQueryService;
        this.meterRegistry = meterRegistry;
    }

    @GetMapping
    public ResponseEntity<?> getBalance(
            @RequestParam String accountId,
            @RequestParam String balanceType,
            @RequestParam(required = false) String position,
            @RequestParam String currency) {
        long start = System.nanoTime();
        String queryType = (position != null && !position.isEmpty()) ? "live-position" : "live";
        try {
            BalanceQueryResult result;
            if (position != null && !position.isEmpty()) {
                result = balanceQueryService.getBalanceByPosition(accountId, balanceType, position, currency);
            } else {
                result = balanceQueryService.getBalance(accountId, balanceType, currency);
            }
            return ResponseEntity.ok(result);
        } finally {
            meterRegistry.timer("ledger.balance.query.duration", "queryType", queryType)
                    .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    @GetMapping("/as-of")
    public ResponseEntity<?> getAsOfBalance(
            @RequestParam String accountId,
            @RequestParam String balanceType,
            @RequestParam String currency,
            @RequestParam String asOf) {
        long start = System.nanoTime();
        try {
            BalanceQueryResult result = balanceQueryService.getAsOfBalance(
                    accountId, balanceType, currency, LocalDate.parse(asOf));
            return ResponseEntity.ok(result);
        } finally {
            meterRegistry.timer("ledger.balance.query.duration", "queryType", "asof")
                    .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    @PostMapping("/batch")
    public ResponseEntity<?> getBatchBalances(@RequestBody List<AccountBalanceKey> keys) {
        long start = System.nanoTime();
        try {
            List<BalanceQueryResult> results = balanceQueryService.getBatchBalances(keys);
            return ResponseEntity.ok(results);
        } finally {
            meterRegistry.timer("ledger.balance.query.duration", "queryType", "batch")
                    .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }
}

package com.ibank.ledger.rest.controller;

import com.ibank.ledger.domain.model.AccountingPeriod;
import com.ibank.ledger.service.AccountingPeriodService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/ledger/periods")
public class AccountPeriodController {

    private final AccountingPeriodService periodService;

    public AccountPeriodController(AccountingPeriodService periodService) {
        this.periodService = periodService;
    }

    @PostMapping("/eod")
    public ResponseEntity<?> triggerEOD(@RequestBody Map<String, String> body) {
        LocalDate date = LocalDate.parse(body.get("date"));
        periodService.triggerEOD(date);
        AccountingPeriod period = periodService.getPeriod(date);
        return ResponseEntity.ok(Map.of(
                "date", date.toString(),
                "status", period.status().name()));
    }

    @GetMapping
    public ResponseEntity<?> getPeriod(@RequestParam String date) {
        AccountingPeriod period = periodService.getPeriod(LocalDate.parse(date));
        return ResponseEntity.ok(period);
    }
}

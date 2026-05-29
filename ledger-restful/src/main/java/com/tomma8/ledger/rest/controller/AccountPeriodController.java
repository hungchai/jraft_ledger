package com.tomma8.ledger.rest.controller;

import com.tomma8.ledger.domain.model.AccountingPeriod;
import com.tomma8.ledger.service.AccountingPeriodService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
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
        var resp = new HashMap<String, Object>();
        resp.put("date", date.toString());
        resp.put("status", period.status().name());
        return ResponseEntity.ok(resp);
    }

    @GetMapping
    public ResponseEntity<?> getPeriod(@RequestParam String date) {
        AccountingPeriod period = periodService.getPeriod(LocalDate.parse(date));
        return ResponseEntity.ok(period);
    }
}

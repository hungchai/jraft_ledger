package com.example.ledger.controller;

import com.example.ledger.dto.AccountResponse;
import com.example.ledger.dto.TransferRequest;
import com.example.ledger.model.Account;
import com.example.ledger.service.LedgerService;
import com.example.ledger.service.RaftService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequestMapping("/api/ledger")
@RequiredArgsConstructor
@Validated
public class LedgerController {
    
    private final LedgerService ledgerService;
    private final RaftService raftService;
    
    @PostMapping("/accounts")
    public ResponseEntity<?> createAccount(@RequestBody Account account) {
        if (!raftService.isLeader()) {
            return ResponseEntity.status(503)
                .body(Map.of("error", "Not leader", "leader", raftService.getLeaderEndpoint()));
        }
        ledgerService.createAccount(account.getAccountNumber(), account.getAccountName(), 
            account.getAccountType(), account.getBalance());
        return ResponseEntity.ok().build();
    }
    
    @GetMapping("/accounts")
    public ResponseEntity<List<AccountResponse>> getAllAccounts() {
        if (!raftService.isLeader()) {
            return ResponseEntity.status(503)
                .body(List.of());
        }
        return ResponseEntity.ok(ledgerService.getAllAccounts());
    }
    
    @GetMapping("/accounts/{accountNumber}")
    public ResponseEntity<AccountResponse> getAccount(@PathVariable String accountNumber) {
        if (!raftService.isLeader()) {
            return ResponseEntity.status(503)
                .body(null);
        }
        try {
            AccountResponse account = ledgerService.getAccount(accountNumber);
            return ResponseEntity.ok(account);
        } catch (RuntimeException e) {
            if (e.getMessage().contains("Account not found")) {
                return ResponseEntity.notFound().build();
            }
            throw e;
        }
    }
    
    @PostMapping("/transfer")
    public ResponseEntity<?> executeTransfer(@RequestBody TransferRequest request) {
        if (!raftService.isLeader()) {
            return ResponseEntity.status(503)
                .body(Map.of("error", "Not leader", "leader", raftService.getLeaderEndpoint()));
        }
        ledgerService.executeTransfer(request);
        return ResponseEntity.ok().build();
    }
    
    @PostMapping("/batch-transfer")
    public ResponseEntity<?> executeBatchTransfer(@RequestBody TransferRequest[] requests) {
        if (!raftService.isLeader()) {
            return ResponseEntity.status(503)
                .body(Map.of("error", "Not leader", "leader", raftService.getLeaderEndpoint()));
        }
        ledgerService.executeBatchTransfer(requests);
        return ResponseEntity.ok().build();
    }
    
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(Map.of(
            "nodeId", raftService.getNodeId(),
            "isLeader", raftService.isLeader(),
            "leader", raftService.getLeaderEndpoint(),
            "accountCount", ledgerService.getAllAccounts().size()
        ));
    }
} 
package com.example.ledger.controller;

import com.example.ledger.model.Account;
import com.example.ledger.service.LedgerService;
import com.example.ledger.service.IdempotencyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequestMapping("/api/transfer")
@Tag(name = "Transfer API", description = "转账相关接口")
public class TransferController {

    @Autowired
    private LedgerService ledgerService;
    
    @Autowired
    private IdempotencyService idempotencyService;

    @PostMapping("/single")
    @Operation(summary = "单笔转账", description = "执行单笔复式记账转账，支持幂等性")
    public CompletableFuture<ResponseEntity<TransferResponse>> singleTransfer(
            @RequestBody SingleTransferRequest request,
            @Parameter(description = "幂等性键，用于防止重复提交") 
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        
        log.info("Processing single transfer: {} {} -> {} {}, amount: {}, idempotencyKey: {}", 
            request.getFromUserId(), request.getFromType(),
            request.getToUserId(), request.getToType(), request.getAmount(), idempotencyKey);
        
        // Generate idempotency key if not provided
        final String finalIdempotencyKey = (idempotencyKey == null || idempotencyKey.trim().isEmpty()) 
            ? idempotencyService.generateIdempotencyKey(request) 
            : idempotencyKey;
        
        // Check if this request has already been processed
        IdempotencyService.IdempotencyResult existingResult = idempotencyService.checkIdempotency(finalIdempotencyKey);
        if (existingResult != null) {
            log.info("Returning cached result for idempotency key: {}", finalIdempotencyKey);
            return CompletableFuture.completedFuture(
                ResponseEntity.status(existingResult.getStatusCode())
                    .body(new TransferResponse(existingResult.isSuccess(), existingResult.getMessage()))
            );
        }
        
        // Mark request as processing
        idempotencyService.markProcessing(finalIdempotencyKey);
        
        return ledgerService.transfer(
            request.getFromUserId(), request.getFromType(),
            request.getToUserId(), request.getToType(),
            request.getAmount(), request.getDescription(), finalIdempotencyKey
        ).thenApply(success -> {
            ResponseEntity<TransferResponse> response;
            if (success) {
                response = ResponseEntity.ok(new TransferResponse(true, "Transfer completed successfully"));
                idempotencyService.storeResult(finalIdempotencyKey, true, "Transfer completed successfully", 200);
            } else {
                response = ResponseEntity.badRequest().body(new TransferResponse(false, "Transfer failed"));
                idempotencyService.storeResult(finalIdempotencyKey, false, "Transfer failed", 400);
            }
            return response;
        }).exceptionally(ex -> {
            String msg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
            ResponseEntity<TransferResponse> response = ResponseEntity.status(404)
                .body(new TransferResponse(false, msg));
            idempotencyService.storeResult(finalIdempotencyKey, false, msg, 404);
            return response;
        });
    }

    @PostMapping("/batch")
    @Operation(summary = "批量转账", description = "执行多笔原子性复式记账转账")
    public CompletableFuture<ResponseEntity<TransferResponse>> batchTransfer(
            @RequestBody BatchTransferRequest request,
            @Parameter(description = "批量转账幂等性键，必填") 
            @RequestHeader(value = "Idempotency-Key", required = true) String idempotencyKey) {
        
        log.info("Processing batch transfer with {} transactions, idempotencyKey: {}", 
                request.getTransfers().size(), idempotencyKey);
        
        // Validate idempotency key
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            return CompletableFuture.completedFuture(
                ResponseEntity.badRequest().body(new TransferResponse(false, "Idempotency-Key header is mandatory for batch transfers"))
            );
        }
        
        return ledgerService.batchTransfer(request.getTransfers(), idempotencyKey.trim())
            .thenApply(success -> {
                if (success) {
                    return ResponseEntity.ok(new TransferResponse(true, "Batch transfer completed successfully"));
                } else {
                    return ResponseEntity.badRequest().body(new TransferResponse(false, "Batch transfer failed"));
                }
            }).exceptionally(ex -> {
                String msg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                if (msg.contains("idempotentId is mandatory")) {
                    return ResponseEntity.badRequest().body(new TransferResponse(false, msg));
                }
                return ResponseEntity.status(500).body(new TransferResponse(false, "Batch transfer error: " + msg));
            });
    }

    @PostMapping("/demo")
    @Operation(summary = "演示转账", description = "演示你的用例：UserA.Available -> 10 -> UserB.Available 和 UserA.Available -> 20 -> Bank.Available")
    public CompletableFuture<ResponseEntity<TransferResponse>> demoTransfer(
            @Parameter(description = "演示转账幂等性键，必填") 
            @RequestHeader(value = "Idempotency-Key", required = true) String idempotencyKey) {
        
        // Validate idempotency key
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            return CompletableFuture.completedFuture(
                ResponseEntity.badRequest().body(new TransferResponse(false, "Idempotency-Key header is mandatory for demo transfers"))
            );
        }
        
        // 先创建账户
        CompletableFuture<Boolean> createAccounts = CompletableFuture
            .allOf(
                ledgerService.createAccount("UserA", Account.AccountType.AVAILABLE).toCompletableFuture(),
                ledgerService.createAccount("UserB", Account.AccountType.AVAILABLE).toCompletableFuture(),
                ledgerService.createAccount("Bank", Account.AccountType.AVAILABLE).toCompletableFuture()
            )
            .thenApply(v -> true);

        return createAccounts.thenCompose(created -> {
            // 然后执行批量转账
            List<LedgerService.TransferRequest> transfers = List.of(
                new LedgerService.TransferRequest("UserA", Account.AccountType.AVAILABLE, 
                                                "UserB", Account.AccountType.AVAILABLE, 
                                                new BigDecimal("10.00"), "Demo transfer to UserB"),
                new LedgerService.TransferRequest("UserA", Account.AccountType.AVAILABLE, 
                                                "Bank", Account.AccountType.AVAILABLE, 
                                                new BigDecimal("20.00"), "Demo transfer to Bank")
            );
            
            return ledgerService.batchTransfer(transfers, idempotencyKey.trim());
        }).thenApply(success -> {
            if (success) {
                return ResponseEntity.ok(new TransferResponse(true, 
                    "Demo transfers completed: UserA->UserB(10) and UserA->Bank(20)"));
            } else {
                return ResponseEntity.badRequest().body(new TransferResponse(false, "Demo transfers failed"));
            }
        });
    }

    // DTOs
    public static class SingleTransferRequest {
        private String fromUserId;
        private Account.AccountType fromType;
        private String toUserId;
        private Account.AccountType toType;
        private BigDecimal amount;
        private String description;

        // Getters and Setters
        public String getFromUserId() { return fromUserId; }
        public void setFromUserId(String fromUserId) { this.fromUserId = fromUserId; }

        public Account.AccountType getFromType() { return fromType; }
        public void setFromType(Account.AccountType fromType) { this.fromType = fromType; }

        public String getToUserId() { return toUserId; }
        public void setToUserId(String toUserId) { this.toUserId = toUserId; }

        public Account.AccountType getToType() { return toType; }
        public void setToType(Account.AccountType toType) { this.toType = toType; }

        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }

    public static class BatchTransferRequest {
        private List<LedgerService.TransferRequest> transfers;

        public List<LedgerService.TransferRequest> getTransfers() { return transfers; }
        public void setTransfers(List<LedgerService.TransferRequest> transfers) { this.transfers = transfers; }
    }

    public static class TransferResponse {
        private boolean success;
        private String message;

        public TransferResponse(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }
}

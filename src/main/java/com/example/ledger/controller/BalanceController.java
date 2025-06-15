package com.example.ledger.controller;

import com.example.ledger.model.Account;
import com.example.ledger.service.LedgerService;
import com.example.ledger.service.AccountBusinessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequestMapping("/api/balance")
@Tag(name = "Balance API", description = "余额查询相关接口")
public class BalanceController {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private AccountBusinessService accountBusinessService;

    @GetMapping("/user/{userId}")
    @Operation(summary = "查询用户所有账户余额", description = "查询指定用户的所有账户类型余额")
    public ResponseEntity<LedgerService.UserBalances> getUserBalances(@PathVariable String userId) {
        log.info("Querying balances for user: {}", userId);
        
        LedgerService.UserBalances balances = ledgerService.getUserBalances(userId);
        return ResponseEntity.ok(balances);
    }

    @GetMapping("/account/{userId}/{accountType}")
    @Operation(summary = "查询特定账户余额", description = "查询指定用户特定账户类型的余额")
    public ResponseEntity<AccountBalanceResponse> getAccountBalance(
            @PathVariable String userId,
            @PathVariable String accountType) {
        
        log.info("Querying balance for user: {}, account type: {}", userId, accountType);
        
        try {
            Account.AccountType type = Account.AccountType.fromValue(accountType);
            BigDecimal balance = ledgerService.getBalance(userId, type);
            
            AccountBalanceResponse response = new AccountBalanceResponse();
            response.setUserId(userId);
            response.setAccountType(type);
            response.setBalance(balance);
            
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.error("Invalid account type: {}", accountType, e);
            return ResponseEntity.badRequest().body(null);
        }
    }

    @PostMapping("/create")
    @Operation(summary = "创建账户", description = "为用户创建指定类型的账户")
    public CompletableFuture<ResponseEntity<CreateAccountResponse>> createAccount(
            @RequestBody CreateAccountRequest request) {
        
        log.info("Creating account for user: {}, type: {}", request.getUserId(), request.getAccountType());
        
        String accountId = Account.generateAccountId(request.getUserId(), request.getAccountType());
        if (accountBusinessService.accountExists(accountId)) {
            return CompletableFuture.completedFuture(
                ResponseEntity.ok(new CreateAccountResponse(true, "Account already exists")));
        }
        
        return ledgerService.createAccount(request.getUserId(), request.getAccountType())
            .thenApply(success -> {
                if (success) {
                    return ResponseEntity.ok(new CreateAccountResponse(true, "Account created successfully"));
                } else {
                    return ResponseEntity.badRequest().body(new CreateAccountResponse(false, "Failed to create account"));
                }
            });
    }

    // DTOs
    public static class AccountBalanceResponse {
        private String userId;
        private Account.AccountType accountType;
        private BigDecimal balance;

        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }

        public Account.AccountType getAccountType() { return accountType; }
        public void setAccountType(Account.AccountType accountType) { this.accountType = accountType; }

        public BigDecimal getBalance() { return balance; }
        public void setBalance(BigDecimal balance) { this.balance = balance; }
    }

    public static class CreateAccountRequest {
        private String userId;
        private Account.AccountType accountType;

        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }

        public Account.AccountType getAccountType() { return accountType; }
        public void setAccountType(Account.AccountType accountType) { this.accountType = accountType; }
    }

    public static class CreateAccountResponse {
        private boolean success;
        private String message;

        public CreateAccountResponse(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }
}

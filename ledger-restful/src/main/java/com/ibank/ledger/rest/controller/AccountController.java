package com.ibank.ledger.rest.controller;

import com.ibank.ledger.domain.command.AccountCreateCommand;
import com.ibank.ledger.domain.command.AccountFreezeCommand;
import com.ibank.ledger.domain.command.AccountCreateCommand.BalanceInitialization;
import com.ibank.ledger.domain.command.CommandResult;
import com.ibank.ledger.domain.model.*;
import com.ibank.ledger.service.AccountService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/ledger/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    public ResponseEntity<?> createAccount(@RequestBody Map<String, Object> body) {
        String accountId = (String) body.get("accountId");
        AccountType type = AccountType.valueOf((String) body.get("accountType"));
        String displayName = (String) body.get("displayName");
        String ownerId = (String) body.get("ownerId");

        @SuppressWarnings("unchecked")
        List<Map<String, String>> inits = (List<Map<String, String>>) body.get("balanceInitializations");
        List<BalanceInitialization> initList = inits.stream()
                .map(m -> new BalanceInitialization(m.get("balanceType"), m.get("currency")))
                .toList();

        CommandResult result = accountService.createAccount(
                new AccountCreateCommand(body.get("requestId").toString(), accountId, type,
                        displayName, ownerId, initList));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{accountId}/freeze")
    public ResponseEntity<?> freeze(@PathVariable String accountId, @RequestBody Map<String, String> body) {
        CommandResult result = accountService.freezeAccount(
                new AccountFreezeCommand(body.get("requestId"), accountId, true));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{accountId}/unfreeze")
    public ResponseEntity<?> unfreeze(@PathVariable String accountId, @RequestBody Map<String, String> body) {
        CommandResult result = accountService.unfreezeAccount(
                new AccountFreezeCommand(body.get("requestId"), accountId, false));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{accountId}/close")
    public ResponseEntity<?> close(@PathVariable String accountId, @RequestBody Map<String, String> body) {
        CommandResult result = accountService.closeAccount(accountId, body.get("requestId"));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{accountId}/balance-types")
    public ResponseEntity<?> addBalanceType(@PathVariable String accountId, @RequestBody Map<String, String> body) {
        CommandResult result = accountService.addBalanceType(
                accountId, body.get("balanceType"), body.get("currency"), body.get("requestId"));
        return ResponseEntity.ok(result);
    }
}

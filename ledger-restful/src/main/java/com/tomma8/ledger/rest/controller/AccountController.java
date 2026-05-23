package com.tomma8.ledger.rest.controller;

import com.tomma8.ledger.domain.command.AccountCreateCommand;
import com.tomma8.ledger.domain.command.AccountFreezeCommand;
import com.tomma8.ledger.domain.command.AccountCreateCommand.BalanceInitialization;
import com.tomma8.ledger.domain.command.CommandResult;
import com.tomma8.ledger.domain.model.*;
import com.tomma8.ledger.raft.NodeRole;
import com.tomma8.ledger.raft.RaftNodeManager;
import com.tomma8.ledger.service.AccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/ledger/accounts")
public class AccountController {

    private final AccountService accountService;
    private final RaftNodeManager raftNodeManager;
    private final NodeRole nodeRole;

    public AccountController(AccountService accountService,
                              @Autowired(required = false) RaftNodeManager raftNodeManager,
                              NodeRole nodeRole) {
        this.accountService = accountService;
        this.raftNodeManager = raftNodeManager;
        this.nodeRole = nodeRole;
    }

    @PostMapping
    public ResponseEntity<?> createAccount(@RequestBody Map<String, Object> body) {
        if (!nodeRole.isLeader()) {
            String leader = raftNodeManager != null ? raftNodeManager.getLeaderEndpoint() : "unknown";
            return ResponseEntity.status(503).body(Map.of(
                    "status", "REJECTED",
                    "errorCodes", List.of("NOT_LEADER"),
                    "leaderHint", leader));
        }
        String accountId = (String) body.get("accountId");
        AccountType type = AccountType.valueOf((String) body.get("accountType"));
        String displayName = (String) body.get("displayName");
        String ownerId = (String) body.get("ownerId");

        @SuppressWarnings("unchecked")
        List<Map<String, String>> inits = (List<Map<String, String>>) body.get("balanceInitializations");
        List<BalanceInitialization> initList = inits.stream()
                .map(m -> new BalanceInitialization(m.get("balanceType"), m.get("currency")))
                .toList();

        AccountCreateCommand cmd = new AccountCreateCommand(body.get("requestId").toString(),
                accountId, type, displayName, ownerId, initList);

        CommandResult result;
        if (raftNodeManager != null) {
            result = raftNodeManager.submit(cmd);
        } else {
            result = accountService.createAccount(cmd);
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{accountId}/freeze")
    public ResponseEntity<?> freeze(@PathVariable String accountId, @RequestBody Map<String, String> body) {
        if (!nodeRole.isLeader()) {
            String leader = raftNodeManager != null ? raftNodeManager.getLeaderEndpoint() : "unknown";
            return ResponseEntity.status(503).body(Map.of(
                    "status", "REJECTED",
                    "errorCodes", List.of("NOT_LEADER"),
                    "leaderHint", leader));
        }
        AccountFreezeCommand cmd = new AccountFreezeCommand(body.get("requestId"), accountId, true);
        CommandResult result;
        if (raftNodeManager != null) {
            result = raftNodeManager.submit(cmd);
        } else {
            result = accountService.freezeAccount(cmd);
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{accountId}/unfreeze")
    public ResponseEntity<?> unfreeze(@PathVariable String accountId, @RequestBody Map<String, String> body) {
        if (!nodeRole.isLeader()) {
            String leader = raftNodeManager != null ? raftNodeManager.getLeaderEndpoint() : "unknown";
            return ResponseEntity.status(503).body(Map.of(
                    "status", "REJECTED",
                    "errorCodes", List.of("NOT_LEADER"),
                    "leaderHint", leader));
        }
        AccountFreezeCommand cmd = new AccountFreezeCommand(body.get("requestId"), accountId, false);
        CommandResult result;
        if (raftNodeManager != null) {
            result = raftNodeManager.submit(cmd);
        } else {
            result = accountService.unfreezeAccount(cmd);
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{accountId}/close")
    public ResponseEntity<?> close(@PathVariable String accountId, @RequestBody Map<String, String> body) {
        if (!nodeRole.isLeader()) {
            String leader = raftNodeManager != null ? raftNodeManager.getLeaderEndpoint() : "unknown";
            return ResponseEntity.status(503).body(Map.of(
                    "status", "REJECTED",
                    "errorCodes", List.of("NOT_LEADER"),
                    "leaderHint", leader));
        }
        // Close is an AccountCloseCommand, not AccountFreezeCommand
        com.tomma8.ledger.domain.command.AccountCloseCommand cmd =
                new com.tomma8.ledger.domain.command.AccountCloseCommand(body.get("requestId"), accountId);
        CommandResult result;
        if (raftNodeManager != null) {
            result = raftNodeManager.submit(cmd);
        } else {
            result = accountService.closeAccount(accountId, body.get("requestId"));
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{accountId}/balance-types")
    public ResponseEntity<?> addBalanceType(@PathVariable String accountId, @RequestBody Map<String, String> body) {
        if (!nodeRole.isLeader()) {
            String leader = raftNodeManager != null ? raftNodeManager.getLeaderEndpoint() : "unknown";
            return ResponseEntity.status(503).body(Map.of(
                    "status", "REJECTED",
                    "errorCodes", List.of("NOT_LEADER"),
                    "leaderHint", leader));
        }
        com.tomma8.ledger.domain.command.AccountAddBalanceTypeCommand cmd =
                new com.tomma8.ledger.domain.command.AccountAddBalanceTypeCommand(
                        body.get("requestId"), accountId,
                        body.get("balanceType"), body.get("currency"));
        CommandResult result;
        if (raftNodeManager != null) {
            result = raftNodeManager.submit(cmd);
        } else {
            result = accountService.addBalanceType(accountId, body.get("balanceType"), body.get("currency"), body.get("requestId"));
        }
        return ResponseEntity.ok(result);
    }
}

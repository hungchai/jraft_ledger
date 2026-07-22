package com.tomma8.ledger.service;

import com.tomma8.ledger.domain.command.AccountCreateCommand;
import com.tomma8.ledger.domain.command.AccountFreezeCommand;
import com.tomma8.ledger.domain.command.CommandResult;
import com.tomma8.ledger.domain.exception.*;
import com.tomma8.ledger.domain.model.AccountStatus;
import com.tomma8.ledger.domain.model.AccountType;
import com.tomma8.ledger.statemachine.LedgerStateMachine;
import com.tomma8.ledger.store.AccountMetaStore;
import com.tomma8.ledger.store.BalanceTypeConfigStore;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * F-010 Account Management.
 * Account lifecycle: create, freeze, unfreeze, close, add balance type.
 */
public class AccountService {

    private final LedgerStateMachine stateMachine;
    private final BalanceTypeConfigStore balanceTypeConfigStore;
    private final AccountMetaStore accountMetaStore;

    public AccountService(LedgerStateMachine stateMachine, BalanceTypeConfigStore balanceTypeConfigStore) {
        this.stateMachine = stateMachine;
        this.balanceTypeConfigStore = balanceTypeConfigStore;
        this.accountMetaStore = stateMachine.getAccountMetaStore();
    }

    public Optional<com.tomma8.ledger.domain.model.Account> getAccount(String accountId) {
        return accountMetaStore.get(accountId);
    }

    public CommandResult createAccount(AccountCreateCommand cmd) {
        // CLIENT type must have ownerId
        if (cmd.accountType() == AccountType.CLIENT && (cmd.ownerId() == null || cmd.ownerId().isBlank())) {
            throw new MissingOwnerIdException();
        }

        // Validate all balance types exist
        for (var init : cmd.balanceInitializations()) {
            if (!balanceTypeConfigStore.contains(init.balanceType())) {
                throw new BalanceTypeNotFoundException(init.balanceType());
            }
        }

        return stateMachine.applyAccountCreate(cmd);
    }

    public CommandResult freezeAccount(AccountFreezeCommand cmd) {
        return stateMachine.applyFreeze(cmd);
    }

    public CommandResult unfreezeAccount(AccountFreezeCommand cmd) {
        return stateMachine.applyUnfreeze(cmd);
    }

    public CommandResult closeAccount(String accountId, String requestId) {
        return stateMachine.applyCloseAccount(accountId, requestId);
    }

    public CommandResult addBalanceType(String accountId, String balanceType, String currency, String requestId) {
        if (!balanceTypeConfigStore.contains(balanceType)) {
            throw new BalanceTypeNotFoundException(balanceType);
        }
        return stateMachine.applyAddBalanceType(accountId, balanceType, currency, requestId);
    }
}

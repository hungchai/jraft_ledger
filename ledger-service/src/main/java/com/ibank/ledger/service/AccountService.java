package com.ibank.ledger.service;

import com.ibank.ledger.domain.command.AccountCreateCommand;
import com.ibank.ledger.domain.command.AccountFreezeCommand;
import com.ibank.ledger.domain.command.CommandResult;
import com.ibank.ledger.domain.exception.*;
import com.ibank.ledger.domain.model.AccountStatus;
import com.ibank.ledger.domain.model.AccountType;
import com.ibank.ledger.statemachine.LedgerStateMachine;
import com.ibank.ledger.store.BalanceTypeConfigStore;

import java.math.BigDecimal;

/**
 * F-010 Account Management.
 * Account lifecycle: create, freeze, unfreeze, close, add balance type.
 */
public class AccountService {

    private final LedgerStateMachine stateMachine;
    private final BalanceTypeConfigStore balanceTypeConfigStore;

    public AccountService(LedgerStateMachine stateMachine, BalanceTypeConfigStore balanceTypeConfigStore) {
        this.stateMachine = stateMachine;
        this.balanceTypeConfigStore = balanceTypeConfigStore;
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

package com.ibank.ledger.service;

import com.ibank.ledger.domain.command.CommandResult;
import com.ibank.ledger.domain.command.ReversalCommand;
import com.ibank.ledger.statemachine.LedgerStateMachine;

/**
 * F-004 Reversal API.
 */
public class ReversalService {

    private final LedgerStateMachine stateMachine;

    public ReversalService(LedgerStateMachine stateMachine) {
        this.stateMachine = stateMachine;
    }

    public CommandResult reverse(ReversalCommand command) {
        return stateMachine.applyReversal(command);
    }
}

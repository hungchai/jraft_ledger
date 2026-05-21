package com.tomma8.ledger.service;

import com.tomma8.ledger.domain.command.CommandResult;
import com.tomma8.ledger.domain.command.ReversalCommand;
import com.tomma8.ledger.statemachine.LedgerStateMachine;

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

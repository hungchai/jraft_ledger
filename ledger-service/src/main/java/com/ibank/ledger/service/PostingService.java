package com.ibank.ledger.service;

import com.ibank.ledger.domain.command.CommandResult;
import com.ibank.ledger.domain.command.PostingCommand;
import com.ibank.ledger.statemachine.LedgerStateMachine;

/**
 * F-002 Posting API.
 * Delegates to LedgerStateMachine for Raft-based posting.
 */
public class PostingService {

    private final LedgerStateMachine stateMachine;

    public PostingService(LedgerStateMachine stateMachine) {
        this.stateMachine = stateMachine;
    }

    public CommandResult post(PostingCommand command) {
        return stateMachine.applyPosting(command);
    }
}

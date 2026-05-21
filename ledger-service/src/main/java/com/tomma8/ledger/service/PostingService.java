package com.tomma8.ledger.service;

import com.tomma8.ledger.domain.command.CommandResult;
import com.tomma8.ledger.domain.command.PostingCommand;
import com.tomma8.ledger.statemachine.LedgerStateMachine;

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

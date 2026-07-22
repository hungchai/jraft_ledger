package com.tomma8.ledger.service;

import com.tomma8.ledger.domain.model.Journal;
import com.tomma8.ledger.statemachine.LedgerStateMachine;

import java.util.List;

/**
 * F-006 Journal Query.
 * Reads from in-memory journal store (backed by RocksDB, synced to MySQL View Layer).
 */
public class JournalQueryService {

    private final LedgerStateMachine stateMachine;

    public JournalQueryService(LedgerStateMachine stateMachine) {
        this.stateMachine = stateMachine;
    }

    public Journal getJournal(String journalId) {
        return stateMachine.getJournal(journalId);
    }

    public JournalPage getJournalsByAccount(String accountId, int page, int size) {
        List<Journal> journals = stateMachine.getJournalsByAccount(accountId, page, size);
        long totalCount = stateMachine.countJournalsByAccount(accountId);
        return new JournalPage(journals, totalCount, page, size);
    }

    public List<Journal> getJournalsByBusinessEventRef(String businessEventRef) {
        return stateMachine.getJournalsByBusinessEventRef(businessEventRef);
    }

    public List<Journal> getJournalChain(String originalJournalId) {
        return stateMachine.getJournalChain(originalJournalId);
    }

    public Journal getJournalByRequestId(String requestId) {
        return stateMachine.getJournalByRequestId(requestId);
    }

    public record JournalPage(List<Journal> journals, long totalCount, int page, int size) {}
}

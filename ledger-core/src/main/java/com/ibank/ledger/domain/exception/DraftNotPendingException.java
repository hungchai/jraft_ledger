package com.ibank.ledger.domain.exception;

public class DraftNotPendingException extends RuntimeException {
    public DraftNotPendingException(String draftId) {
        super("Draft is not in PENDING_APPROVAL status: " + draftId);
    }
}

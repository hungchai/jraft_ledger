package com.ibank.ledger.domain.exception;

public class DraftExpiredException extends RuntimeException {
    public DraftExpiredException(String draftId) {
        super("Draft has expired: " + draftId);
    }
}

package com.ibank.ledger.domain.exception;

public class MissingOwnerIdException extends RuntimeException {
    public MissingOwnerIdException() {
        super("ownerId is required for CLIENT account type");
    }
}

package com.tomma8.ledger.domain.exception;

public class DuplicateBalanceTypeException extends RuntimeException {
    public DuplicateBalanceTypeException(String typeCode) {
        super("Balance type already exists: " + typeCode);
    }
}

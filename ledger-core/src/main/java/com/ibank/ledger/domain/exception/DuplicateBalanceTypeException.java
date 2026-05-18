package com.ibank.ledger.domain.exception;

public class DuplicateBalanceTypeException extends RuntimeException {
    public DuplicateBalanceTypeException(String typeCode) {
        super("Balance type already exists: " + typeCode);
    }
}

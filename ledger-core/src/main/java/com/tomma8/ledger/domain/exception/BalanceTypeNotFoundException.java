package com.tomma8.ledger.domain.exception;

public class BalanceTypeNotFoundException extends RuntimeException {
    public BalanceTypeNotFoundException(String typeCode) {
        super("Balance type not found: " + typeCode);
    }
}

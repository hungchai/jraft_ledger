package com.tomma8.ledger.domain.exception;

public class BalanceTypeInactiveException extends RuntimeException {
    public BalanceTypeInactiveException(String typeCode) {
        super("Balance type is inactive: " + typeCode);
    }
}

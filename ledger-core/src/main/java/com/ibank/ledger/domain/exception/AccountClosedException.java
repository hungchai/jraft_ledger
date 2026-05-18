package com.ibank.ledger.domain.exception;

public class AccountClosedException extends RuntimeException {
    public AccountClosedException(String accountId) {
        super("Account is closed: " + accountId);
    }
}

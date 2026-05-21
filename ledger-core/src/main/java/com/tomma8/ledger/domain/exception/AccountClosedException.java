package com.tomma8.ledger.domain.exception;

public class AccountClosedException extends RuntimeException {
    public AccountClosedException(String accountId) {
        super("Account is closed: " + accountId);
    }
}

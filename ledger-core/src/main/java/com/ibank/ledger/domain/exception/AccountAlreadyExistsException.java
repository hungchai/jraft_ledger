package com.ibank.ledger.domain.exception;

public class AccountAlreadyExistsException extends RuntimeException {
    public AccountAlreadyExistsException(String accountId) {
        super("Account already exists: " + accountId);
    }
}

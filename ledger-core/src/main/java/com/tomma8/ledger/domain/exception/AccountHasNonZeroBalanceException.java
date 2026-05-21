package com.tomma8.ledger.domain.exception;

public class AccountHasNonZeroBalanceException extends RuntimeException {
    public AccountHasNonZeroBalanceException(String accountId) {
        super("Account has non-zero balance: " + accountId);
    }
}

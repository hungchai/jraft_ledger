package com.ibank.ledger.domain.exception;

public class InsufficientBalanceException extends RuntimeException {
    public InsufficientBalanceException(String accountId, String balanceType) {
        super("Insufficient balance for account: " + accountId + " type: " + balanceType);
    }
}

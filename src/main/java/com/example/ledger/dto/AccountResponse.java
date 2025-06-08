package com.example.ledger.dto;

import com.example.ledger.model.Account;
import lombok.Data;

@Data
public class AccountResponse {
    private String accountNumber;
    private String accountName;
    private String balance;
    private Account.AccountType accountType;

    public static AccountResponse fromEntity(Account account) {
        AccountResponse response = new AccountResponse();
        response.setAccountNumber(account.getAccountNumber());
        response.setAccountName(account.getAccountName());
        response.setBalance(account.getBalance().toString());
        response.setAccountType(account.getAccountType());
        return response;
    }
} 
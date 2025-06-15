package com.example.ledger.service;

import com.example.ledger.model.Account;
import com.example.ledger.model.ProcessedTransaction;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WriteEvent {
    public enum Type {
        TRANSACTION, BALANCE
    }

    private Type type;
    private String accountId;
    private BigDecimal balance;
    private ProcessedTransaction transaction;
    private long eventTime;

    public static WriteEvent forBalance(String accountId, BigDecimal balance) {
        return new WriteEvent(Type.BALANCE, accountId, balance, null, System.currentTimeMillis());
    }

    public static WriteEvent forTransaction(ProcessedTransaction tx) {
        return new WriteEvent(Type.TRANSACTION, null, null, tx, System.currentTimeMillis());
    }
} 
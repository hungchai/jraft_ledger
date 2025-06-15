package com.example.ledger.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LedgerOperation implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private OperationType type;
    private Transaction transaction;
    
    public enum OperationType {
        APPLY_TRANSACTION,
        CREATE_ACCOUNT,
        QUERY_BALANCE
    }
    
    // 创建应用交易操作
    public static LedgerOperation applyTransaction(Transaction transaction) {
        return new LedgerOperation(OperationType.APPLY_TRANSACTION, transaction);
    }
    
    // 创建账户操作
    public static LedgerOperation createAccount(String userId, Account.AccountType accountType) {
        // 创建一个虚拟交易来表示账户创建
        Transaction createAccountTx = new Transaction();
        createAccountTx.setDescription("Create account: " + userId + ":" + accountType.getValue());
        return new LedgerOperation(OperationType.CREATE_ACCOUNT, createAccountTx);
    }
} 
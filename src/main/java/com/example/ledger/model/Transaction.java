package com.example.ledger.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {
    private String transactionId;
    private String description;
    private LocalDateTime timestamp;
    private List<DoubleEntry> entries;
    private TransactionStatus status;
    
    public enum TransactionStatus {
        PENDING,
        COMMITTED,
        FAILED
    }
    
    // 复式记账条目
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DoubleEntry {
        private String fromAccountId;
        private String toAccountId;
        private BigDecimal amount;
        private String description;
        
        public DoubleEntry(String fromUserId, Account.AccountType fromType,
                          String toUserId, Account.AccountType toType,
                          BigDecimal amount, String description) {
            this.fromAccountId = Account.generateAccountId(fromUserId, fromType);
            this.toAccountId = Account.generateAccountId(toUserId, toType);
            this.amount = amount;
            this.description = description;
        }
    }
    
    // 创建交易的工厂方法
    public static Transaction create(String description, List<DoubleEntry> entries) {
        Transaction transaction = new Transaction();
        transaction.setTransactionId(UUID.randomUUID().toString());
        transaction.setDescription(description);
        transaction.setTimestamp(LocalDateTime.now());
        transaction.setEntries(entries);
        transaction.setStatus(TransactionStatus.PENDING);
        return transaction;
    }
    
    // 验证交易平衡性（复式记账必须平衡）
    public boolean isBalanced() {
        BigDecimal totalDebits = BigDecimal.ZERO;
        BigDecimal totalCredits = BigDecimal.ZERO;
        
        for (DoubleEntry entry : entries) {
            totalDebits = totalDebits.add(entry.getAmount());
            totalCredits = totalCredits.add(entry.getAmount());
        }
        
        return totalDebits.compareTo(totalCredits) == 0;
    }
} 
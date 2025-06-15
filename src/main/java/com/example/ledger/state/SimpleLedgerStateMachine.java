package com.example.ledger.state;

import com.example.ledger.config.RocksDBService;
import com.example.ledger.model.Account;
import com.example.ledger.model.ProcessedTransaction;
import com.example.ledger.service.AsyncMySQLBatchWriter;
import com.example.ledger.service.WriteEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Component
public class SimpleLedgerStateMachine {
    
    private final RocksDBService rocksDBService;
    
    @Autowired
    private AsyncMySQLBatchWriter asyncMySQLBatchWriter;
    
    public SimpleLedgerStateMachine(RocksDBService rocksDBService) {
        this.rocksDBService = rocksDBService;
        log.info("SimpleLedgerStateMachine initialized");
    }
    
    public void processTransfer(String data) {
        try {
            // 格式: TRANSFER:fromUserId:fromType:toUserId:toType:amount:description:idempotentId
            String[] parts = data.split(":");
            if (parts.length >= 7) {
                String fromUserId = parts[1];
                Account.AccountType fromType = Account.AccountType.fromValue(parts[2]);
                String toUserId = parts[3];
                Account.AccountType toType = Account.AccountType.fromValue(parts[4]);
                BigDecimal amount = new BigDecimal(parts[5]);
                String description = parts[6];
                String idempotentId = parts.length > 7 ? parts[7] : null;
                
                String fromAccountId = Account.generateAccountId(fromUserId, fromType);
                String toAccountId = Account.generateAccountId(toUserId, toType);
                
                // 检查余额
                BigDecimal fromBalance = getAccountBalance(fromAccountId);
                if (fromBalance.compareTo(amount) < 0) {
                    log.error("Insufficient balance for transfer: {} has {}, needs {}", 
                        fromAccountId, fromBalance, amount);
                    return;
                }
                
                // 执行转账
                BigDecimal newFromBalance = fromBalance.subtract(amount);
                BigDecimal toBalance = getAccountBalance(toAccountId);
                BigDecimal newToBalance = toBalance.add(amount);
                
                // Update RocksDB (fast local storage)
                rocksDBService.put(fromAccountId, newFromBalance.toString());
                rocksDBService.put(toAccountId, newToBalance.toString());
                
                // Enqueue balance updates for async MySQL write
                asyncMySQLBatchWriter.enqueue(WriteEvent.forBalance(fromAccountId, newFromBalance));
                asyncMySQLBatchWriter.enqueue(WriteEvent.forBalance(toAccountId, newToBalance));
                
                // Create and enqueue transaction record
                ProcessedTransaction transaction = new ProcessedTransaction();
                transaction.setTransactionId(UUID.randomUUID().toString());
                transaction.setFromAccountId(fromAccountId);
                transaction.setToAccountId(toAccountId);
                transaction.setAmount(amount);
                transaction.setDescription(description);
                transaction.setIdempotentId(idempotentId);
                transaction.setProcessedAt(LocalDateTime.now());
                transaction.setStatus("COMMITTED");
                
                asyncMySQLBatchWriter.enqueue(WriteEvent.forTransaction(transaction));
                
                log.info("Transfer completed: {} -> {}, amount: {}", 
                    fromAccountId, toAccountId, amount);
            }
        } catch (Exception e) {
            log.error("Failed to process transfer: {}", data, e);
        }
    }
    
    public void processCreateAccount(String data) {
        try {
            // 格式: CREATE_ACCOUNT:userId:accountType
            String[] parts = data.split(":");
            if (parts.length >= 3) {
                String userId = parts[1];
                Account.AccountType accountType = Account.AccountType.fromValue(parts[2]);
                String accountId = Account.generateAccountId(userId, accountType);
                
                if (rocksDBService.get(accountId) == null) {
                    rocksDBService.put(accountId, "0.00");
                    log.info("Created account: {}", accountId);
                }
            }
        } catch (Exception e) {
            log.error("Failed to create account: {}", data, e);
        }
    }
    
    public BigDecimal getAccountBalance(String accountId) {
        String balanceStr = rocksDBService.get(accountId);
        return balanceStr != null ? new BigDecimal(balanceStr) : BigDecimal.ZERO;
    }
    
    public void createAccountIfNotExists(String userId, Account.AccountType accountType) {
        String accountId = Account.generateAccountId(userId, accountType);
        if (rocksDBService.get(accountId) == null) {
            rocksDBService.put(accountId, "0.00");
            log.info("Created account: {}", accountId);
        }
    }
} 
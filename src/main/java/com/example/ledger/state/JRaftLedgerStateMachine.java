package com.example.ledger.state;

import com.alipay.sofa.jraft.Closure;
import com.alipay.sofa.jraft.Iterator;
import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.core.StateMachineAdapter;
import com.alipay.sofa.jraft.error.RaftError;
import com.alipay.sofa.jraft.storage.snapshot.SnapshotReader;
import com.alipay.sofa.jraft.storage.snapshot.SnapshotWriter;
import com.example.ledger.config.RocksDBService;
import com.example.ledger.model.Account;
import com.example.ledger.model.ProcessedTransaction;
import com.example.ledger.service.AsyncMySQLBatchWriter;
import com.example.ledger.service.WriteEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JRaft-enabled Ledger State Machine
 * This state machine handles distributed consensus for ledger operations
 * Only active when raft.enabled=true
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "raft.enabled", havingValue = "true")
public class JRaftLedgerStateMachine extends StateMachineAdapter {
    
    private final RocksDBService rocksDBService;
    private final AtomicLong appliedIndex = new AtomicLong(0);
    
    @Autowired
    private AsyncMySQLBatchWriter asyncMySQLBatchWriter;
    
    public JRaftLedgerStateMachine(RocksDBService rocksDBService) {
        this.rocksDBService = rocksDBService;
        log.info("JRaftLedgerStateMachine initialized with JRaft consensus enabled");
    }
    
    @Override
    public void onApply(Iterator iterator) {
        while (iterator.hasNext()) {
            Status status = Status.OK();
            Closure closure = iterator.done();
            ByteBuffer data = iterator.getData();
            
            try {
                // Parse command from ByteBuffer
                String command = new String(data.array());
                log.debug("Processing JRaft command: {}", command);
                
                // Process the command
                boolean success = processCommand(command);
                
                if (!success) {
                    status = new Status(RaftError.EINTERNAL, "Failed to process command: " + command);
                }
                
                // Update applied index
                appliedIndex.set(iterator.getIndex());
                
            } catch (Exception e) {
                log.error("Error processing JRaft command", e);
                status = new Status(RaftError.EINTERNAL, "Exception: " + e.getMessage());
            }
            
            // Notify completion
            if (closure != null) {
                closure.run(status);
            }
            
            iterator.next();
        }
    }
    
    /**
     * Process individual commands through JRaft consensus
     */
    private boolean processCommand(String command) {
        try {
            String[] parts = command.split(":");
            String operation = parts[0];
            
            switch (operation) {
                case "CREATE_ACCOUNT":
                    return handleCreateAccount(parts);
                case "TRANSFER":
                    return handleTransfer(parts);
                default:
                    log.warn("Unknown command operation: {}", operation);
                    return false;
            }
        } catch (Exception e) {
            log.error("Error processing command: {}", command, e);
            return false;
        }
    }
    
    /**
     * Handle account creation through JRaft consensus
     */
    private boolean handleCreateAccount(String[] parts) {
        if (parts.length < 3) {
            log.error("Invalid CREATE_ACCOUNT command format");
            return false;
        }
        
        String userId = parts[1];
        String accountTypeStr = parts[2];
        
        try {
            Account.AccountType accountType = Account.AccountType.valueOf(accountTypeStr.toUpperCase());
            String accountId = Account.generateAccountId(userId, accountType);
            
            // Check if account already exists
            String existingBalance = rocksDBService.get(accountId);
            if (existingBalance != null) {
                log.info("Account already exists: {}", accountId);
                return true;
            }
            
            // Create account with zero balance
            rocksDBService.put(accountId, "0.0000");
            
            // Enqueue for async MySQL write
            asyncMySQLBatchWriter.enqueue(WriteEvent.forBalance(accountId, BigDecimal.ZERO));
            
            log.info("Created account through JRaft consensus: {}", accountId);
            return true;
            
        } catch (Exception e) {
            log.error("Error creating account: {}", String.join(":", parts), e);
            return false;
        }
    }
    
    /**
     * Handle transfer through JRaft consensus
     */
    private boolean handleTransfer(String[] parts) {
        if (parts.length < 7) {
            log.error("Invalid TRANSFER command format");
            return false;
        }
        
        try {
            String fromUserId = parts[1];
            String fromTypeStr = parts[2];
            String toUserId = parts[3];
            String toTypeStr = parts[4];
            BigDecimal amount = new BigDecimal(parts[5]);
            String description = parts[6];
            String idempotentId = parts.length > 7 ? parts[7] : null;
            
            Account.AccountType fromType = Account.AccountType.valueOf(fromTypeStr.toUpperCase());
            Account.AccountType toType = Account.AccountType.valueOf(toTypeStr.toUpperCase());
            
            String fromAccountId = Account.generateAccountId(fromUserId, fromType);
            String toAccountId = Account.generateAccountId(toUserId, toType);
            
            // Validate transfer
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                log.error("Invalid transfer amount: {}", amount);
                return false;
            }
            
            // Get current balances
            BigDecimal fromBalance = getAccountBalance(fromAccountId);
            BigDecimal toBalance = getAccountBalance(toAccountId);
            
            // Check sufficient funds
            if (fromBalance.compareTo(amount) < 0) {
                log.error("Insufficient funds: {} < {}", fromBalance, amount);
                return false;
            }
            
            // Execute transfer
            BigDecimal newFromBalance = fromBalance.subtract(amount);
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
            
            log.info("JRaft transfer completed: {} -> {}, amount: {}", 
                fromAccountId, toAccountId, amount);
            return true;
            
        } catch (Exception e) {
            log.error("Error processing transfer: {}", String.join(":", parts), e);
            return false;
        }
    }
    
    /**
     * Get account balance from RocksDB
     */
    private BigDecimal getAccountBalance(String accountId) {
        try {
            String balanceStr = rocksDBService.get(accountId);
            if (balanceStr == null) {
                // Create account with zero balance if not exists
                rocksDBService.put(accountId, "0.0000");
                return BigDecimal.ZERO;
            }
            return new BigDecimal(balanceStr);
        } catch (Exception e) {
            log.error("Error getting balance for account: {}", accountId, e);
            return BigDecimal.ZERO;
        }
    }
    
    public boolean saveSnapshot(SnapshotWriter writer) {
        log.info("Saving JRaft snapshot at index: {}", appliedIndex.get());
        try {
            // TODO: Implement snapshot saving
            // This would typically save the current state of RocksDB to the snapshot
            return true;
        } catch (Exception e) {
            log.error("Error saving snapshot", e);
            return false;
        }
    }
    
    public boolean loadSnapshot(SnapshotReader reader) {
        log.info("Loading JRaft snapshot");
        try {
            // TODO: Implement snapshot loading
            // This would typically restore RocksDB state from the snapshot
            return true;
        } catch (Exception e) {
            log.error("Error loading snapshot", e);
            return false;
        }
    }
    
    @Override
    public void onLeaderStart(long term) {
        log.info("JRaft node became LEADER at term: {}", term);
        super.onLeaderStart(term);
    }
    
    @Override
    public void onLeaderStop(Status status) {
        log.info("JRaft node stopped being LEADER, status: {}", status);
        super.onLeaderStop(status);
    }
    
    public long getAppliedIndex() {
        return appliedIndex.get();
    }
} 
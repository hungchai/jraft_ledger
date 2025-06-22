package com.example.ledger.service;

import com.alipay.sofa.jraft.Node;
import com.alipay.sofa.jraft.entity.Task;
import com.example.ledger.model.Account;
import com.example.ledger.model.Transaction;
import com.example.ledger.raft.RaftNodeManager;
import com.example.ledger.state.SimpleLedgerStateMachine;
import com.example.ledger.state.JRaftLedgerStateMachine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class LedgerService {
    
    @Value("${raft.enabled:false}")
    private boolean raftEnabled;
    
    @Autowired(required = false)
    private RaftNodeManager raftNodeManager;
    
    @Autowired
    private SimpleLedgerStateMachine ledgerStateMachine;
    
    @Autowired(required = false)
    private JRaftLedgerStateMachine jraftLedgerStateMachine;
    
    @Autowired(required = false)
    private DataInitializationService dataInitializationService;
    
    @Autowired
    private AsyncMySQLBatchWriter asyncMySQLBatchWriter;
    
    @Autowired
    private AccountBusinessService accountBusinessService;
    
    /**
     * 创建账户
     */
    public CompletableFuture<Boolean> createAccount(String userId, Account.AccountType accountType) {
        String accountId = Account.generateAccountId(userId, accountType);
        
        // First check if account exists
        if (accountBusinessService.accountExists(accountId)) {
            return CompletableFuture.completedFuture(true); // Account already exists
        }
        
        if (raftEnabled && raftNodeManager != null) {
            // Use JRaft consensus for distributed environment
            String command = String.format("CREATE_ACCOUNT:%s:%s", userId, accountType.getValue());
            return submitToRaft(command);
        } else {
            // Use direct call for standalone mode
            ledgerStateMachine.createAccountIfNotExists(userId, accountType);
            // Persist to RocksDB and enqueue for MySQL (if service is available)
            if (dataInitializationService != null) {
                dataInitializationService.updateAccountBalance(accountId, BigDecimal.ZERO);
            }
            return CompletableFuture.completedFuture(true);
        }
    }
    
    /**
     * 单笔转账（复式记账）
     */
    public CompletableFuture<Boolean> transfer(String fromUserId, Account.AccountType fromType,
                                               String toUserId, Account.AccountType toType,
                                               BigDecimal amount, String description) {
        return transfer(fromUserId, fromType, toUserId, toType, amount, description, null);
    }
    
    /**
     * 单笔转账（复式记账）with idempotency key
     */
    public CompletableFuture<Boolean> transfer(String fromUserId, Account.AccountType fromType,
                                               String toUserId, Account.AccountType toType,
                                               BigDecimal amount, String description, String idempotentId) {
        String fromAccountId = Account.generateAccountId(fromUserId, fromType);
        String toAccountId = Account.generateAccountId(toUserId, toType);
        // Check existence in RocksDB (fast path)
        boolean fromExists = accountBusinessService.accountExists(fromAccountId);
        boolean toExists = accountBusinessService.accountExists(toAccountId);
        if (!fromExists || !toExists) {
            String msg = !fromExists ? ("Source account does not exist: " + fromAccountId)
                                      : ("Destination account does not exist: " + toAccountId);
            CompletableFuture<Boolean> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalArgumentException(msg));
            return failed;
        }
        String command = String.format("TRANSFER:%s:%s:%s:%s:%s:%s:%s",
            fromUserId, fromType.getValue(), toUserId, toType.getValue(), amount.toString(), description, 
            idempotentId != null ? idempotentId : "");
        
        if (raftEnabled && raftNodeManager != null) {
            // Use JRaft consensus for distributed environment
            return submitToRaft(command);
        } else {
            // Use direct call for standalone mode
            ledgerStateMachine.processTransfer(command);
            return CompletableFuture.completedFuture(true);
        }
    }
    
    /**
     * 批量转账（原子性多个复式记账）
     */
    public CompletableFuture<Boolean> batchTransfer(List<TransferRequest> transfers) {
        CompletableFuture<Boolean> result = CompletableFuture.completedFuture(true);
        
        for (TransferRequest transfer : transfers) {
            result = result.thenCompose(success -> {
                if (success) {
                    return transfer(transfer.getFromUserId(), transfer.getFromType(),
                                  transfer.getToUserId(), transfer.getToType(),
                                  transfer.getAmount(), transfer.getDescription());
                } else {
                    return CompletableFuture.completedFuture(false);
                }
            });
        }
        
        return result;
    }
    
    /**
     * 查询账户余额 (优先从RocksDB获取，提高性能)
     */
    public BigDecimal getBalance(String userId, Account.AccountType accountType) {
        try {
            String accountId = Account.generateAccountId(userId, accountType);
            
            // First try to get from RocksDB (faster)
            BigDecimal balance = accountBusinessService.getAccountBalance(accountId);
            if (balance != null && balance.compareTo(BigDecimal.ZERO) >= 0) {
                return balance;
            }
            
            // Try DataInitializationService if available
            if (dataInitializationService != null) {
                balance = dataInitializationService.getAccountBalance(accountId);
                if (balance != null && balance.compareTo(BigDecimal.ZERO) >= 0) {
                    return balance;
                }
            }
            
            // Fallback to state machine
            return ledgerStateMachine.getAccountBalance(accountId);
        } catch (Exception e) {
            log.error("Failed to get balance for {}:{}", userId, accountType, e);
            return BigDecimal.ZERO;
        }
    }
    
    /**
     * 查询用户所有账户余额
     */
    public UserBalances getUserBalances(String userId) {
        UserBalances balances = new UserBalances();
        balances.setUserId(userId);
        balances.setBrokerageBalance(getBalance(userId, Account.AccountType.BROKERAGE));
        balances.setExchangeBalance(getBalance(userId, Account.AccountType.EXCHANGE));
        balances.setAvailableBalance(getBalance(userId, Account.AccountType.AVAILABLE));
        return balances;
    }
    
    private CompletableFuture<Boolean> submitToRaft(String command) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        
        try {
            Node node = raftNodeManager.getNode();
            if (node == null) {
                future.complete(false);
                return future;
            }
            
            Task task = new Task();
            task.setData(ByteBuffer.wrap(command.getBytes()));
            task.setDone(status -> {
                if (status.isOk()) {
                    future.complete(true);
                } else {
                    log.error("Raft operation failed: {}", status);
                    future.complete(false);
                }
            });
            
            node.apply(task);
        } catch (Exception e) {
            log.error("Failed to submit to raft: {}", command, e);
            future.complete(false);
        }
        
        return future;
    }
    
    // 轉帳請求DTO
    public static class TransferRequest {
        private String fromUserId;
        private Account.AccountType fromType;
        private String toUserId;
        private Account.AccountType toType;
        private BigDecimal amount;
        private String description;
        
        // Constructors
        public TransferRequest() {}
        
        public TransferRequest(String fromUserId, Account.AccountType fromType,
                             String toUserId, Account.AccountType toType,
                             BigDecimal amount, String description) {
            this.fromUserId = fromUserId;
            this.fromType = fromType;
            this.toUserId = toUserId;
            this.toType = toType;
            this.amount = amount;
            this.description = description;
        }
        
        // Getters and Setters
        public String getFromUserId() { return fromUserId; }
        public void setFromUserId(String fromUserId) { this.fromUserId = fromUserId; }
        
        public Account.AccountType getFromType() { return fromType; }
        public void setFromType(Account.AccountType fromType) { this.fromType = fromType; }
        
        public String getToUserId() { return toUserId; }
        public void setToUserId(String toUserId) { this.toUserId = toUserId; }
        
        public Account.AccountType getToType() { return toType; }
        public void setToType(Account.AccountType toType) { this.toType = toType; }
        
        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }
        
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }
    
    // 用戶餘額DTO
    public static class UserBalances {
        private String userId;
        private BigDecimal brokerageBalance;
        private BigDecimal exchangeBalance;
        private BigDecimal availableBalance;
        
        // Getters and Setters
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        
        public BigDecimal getBrokerageBalance() { return brokerageBalance; }
        public void setBrokerageBalance(BigDecimal brokerageBalance) { this.brokerageBalance = brokerageBalance; }
        
        public BigDecimal getExchangeBalance() { return exchangeBalance; }
        public void setExchangeBalance(BigDecimal exchangeBalance) { this.exchangeBalance = exchangeBalance; }
        
        public BigDecimal getAvailableBalance() { return availableBalance; }
        public void setAvailableBalance(BigDecimal availableBalance) { this.availableBalance = availableBalance; }
    }
} 
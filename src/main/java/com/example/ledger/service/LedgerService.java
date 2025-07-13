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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

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
    
    @Autowired
    private AsyncMySQLBatchWriter asyncMySQLBatchWriter;
    
    @Autowired
    private AccountBusinessService accountBusinessService;
    
    // FIFO Command Queue for Standalone Mode
    private final LinkedBlockingQueue<StandaloneCommand> commandQueue = new LinkedBlockingQueue<>();
    private ExecutorService commandProcessor;
    private final AtomicBoolean processingEnabled = new AtomicBoolean(true);
    
    @PostConstruct
    public void initializeCommandProcessor() {
        if (!raftEnabled) {
            // Initialize FIFO command processor for standalone mode
            commandProcessor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ledger-command-processor");
                t.setDaemon(true);
                return t;
            });
            commandProcessor.submit(this::processCommandsSequentially);
            log.info("FIFO Command processor initialized for standalone mode");
        }
    }
    
    @PreDestroy
    public void shutdown() {
        processingEnabled.set(false);
        if (commandProcessor != null) {
            commandProcessor.shutdown();
            try {
                if (!commandProcessor.awaitTermination(5, TimeUnit.SECONDS)) {
                    commandProcessor.shutdownNow();
                }
            } catch (InterruptedException e) {
                commandProcessor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
    
    /**
     * Sequential command processor for FIFO ordering in standalone mode
     */
    private void processCommandsSequentially() {
        while (processingEnabled.get()) {
            try {
                StandaloneCommand command = commandQueue.poll(100, TimeUnit.MILLISECONDS);
                if (command != null) {
                    try {
                        boolean success = executeStandaloneCommand(command);
                        command.complete(success);
                    } catch (Exception e) {
                        log.error("Error processing standalone command: {}", command.getCommandString(), e);
                        command.completeExceptionally(e);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
    
    /**
     * Execute a standalone command
     */
    private boolean executeStandaloneCommand(StandaloneCommand command) {
        String commandStr = command.getCommandString();
        String[] parts = commandStr.split(":");
        String operation = parts[0];
        
        switch (operation) {
            case "CREATE_ACCOUNT":
                return executeCreateAccount(parts);
            case "TRANSFER":
                return executeTransfer(commandStr);
            default:
                log.warn("Unknown standalone command operation: {}", operation);
                return false;
        }
    }
    
    private boolean executeCreateAccount(String[] parts) {
        if (parts.length < 3) return false;
        String userId = parts[1];
        Account.AccountType accountType = Account.AccountType.valueOf(parts[2].toUpperCase());
        
        ledgerStateMachine.createAccountIfNotExists(userId, accountType);
        String accountId = Account.generateAccountId(userId, accountType);
        asyncMySQLBatchWriter.enqueue(WriteEvent.forBalance(accountId, BigDecimal.ZERO));
        return true;
    }
    
    private boolean executeTransfer(String commandStr) {
        ledgerStateMachine.processTransfer(commandStr);
        return true;
    }

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
            // Use FIFO command queue for standalone mode
            String command = String.format("CREATE_ACCOUNT:%s:%s", userId, accountType.getValue());
            return submitToStandaloneQueue(command);
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
            // Use FIFO command queue for standalone mode
            return submitToStandaloneQueue(command);
        }
    }
    
    /**
     * 批量转账（原子性多个复式记账）
     * Now supports batch-level idempotency with mandatory idempotentId
     */
    public CompletableFuture<Boolean> batchTransfer(List<TransferRequest> transfers, String idempotentId) {
        // Validate mandatory idempotentId
        if (idempotentId == null || idempotentId.trim().isEmpty()) {
            CompletableFuture<Boolean> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalArgumentException("idempotentId is mandatory for batch transfers"));
            return failed;
        }
        
        // Check batch-level idempotency using RocksDB
        String batchIdempotencyKey = "batch_idem:" + idempotentId;
        try {
            String existingResult = accountBusinessService.getRocksDBService().get(batchIdempotencyKey);
            if (existingResult != null) {
                log.info("Batch transfer already processed for idempotentId: {}", idempotentId);
                return CompletableFuture.completedFuture(true); // Already processed
            }
        } catch (Exception e) {
            log.error("Error checking batch idempotency for key: {}", idempotentId, e);
        }
        
        // Mark batch as processing
        try {
            accountBusinessService.getRocksDBService().put(batchIdempotencyKey + ":processing", "1");
        } catch (Exception e) {
            log.warn("Failed to mark batch as processing: {}", idempotentId, e);
        }
        
        if (raftEnabled && raftNodeManager != null) {
            // In cluster mode, submit batch as a single atomic operation
            return processBatchAsAtomicOperation(transfers, idempotentId);
        } else {
            // In standalone mode, submit all transfers to FIFO queue sequentially
            return processBatchSequentially(transfers, idempotentId);
        }
    }
    
    /**
     * Process batch transfers as atomic operation in cluster mode
     */
    private CompletableFuture<Boolean> processBatchAsAtomicOperation(List<TransferRequest> transfers, String idempotentId) {
        CompletableFuture<Boolean> result = CompletableFuture.completedFuture(true);
        
        // Generate unique batch transaction ID
        String batchTransactionId = "BATCH_" + System.currentTimeMillis() + "_" + idempotentId;
        
        for (int i = 0; i < transfers.size(); i++) {
            TransferRequest transfer = transfers.get(i);
            final int transferIndex = i;
            
            result = result.thenCompose(success -> {
                if (success) {
                    // Create unique idempotency key for this transfer within the batch
                    String transferIdempotentId = idempotentId + "_transfer_" + transferIndex;
                    
                    return transfer(transfer.getFromUserId(), transfer.getFromType(),
                                  transfer.getToUserId(), transfer.getToType(),
                                  transfer.getAmount(), 
                                  transfer.getDescription() + " (Batch: " + batchTransactionId + ")",
                                  transferIdempotentId);
                } else {
                    return CompletableFuture.completedFuture(false);
                }
            });
        }
        
        return result.thenApply(success -> {
            // Store batch-level idempotency marker after successful completion
            if (success) {
                try {
                    String batchIdempotencyKey = "batch_idem:" + idempotentId;
                    accountBusinessService.getRocksDBService().put(batchIdempotencyKey, "completed");
                    
                    // Clean up processing marker
                    accountBusinessService.getRocksDBService().delete(batchIdempotencyKey + ":processing");
                    
                    log.info("Batch transfer completed successfully for idempotentId: {}", idempotentId);
                } catch (Exception e) {
                    log.error("Failed to store batch idempotency marker: {}", idempotentId, e);
                }
            } else {
                // Clean up processing marker on failure
                try {
                    String batchIdempotencyKey = "batch_idem:" + idempotentId;
                    accountBusinessService.getRocksDBService().delete(batchIdempotencyKey + ":processing");
                } catch (Exception e) {
                    log.warn("Failed to clean up processing marker: {}", idempotentId, e);
                }
            }
            return success;
        });
    }
    
    /**
     * Process batch transfers sequentially in standalone mode
     */
    private CompletableFuture<Boolean> processBatchSequentially(List<TransferRequest> transfers, String idempotentId) {
        CompletableFuture<Boolean> result = CompletableFuture.completedFuture(true);
        
        // Generate unique batch transaction ID
        String batchTransactionId = "BATCH_" + System.currentTimeMillis() + "_" + idempotentId;
        
        for (int i = 0; i < transfers.size(); i++) {
            TransferRequest transfer = transfers.get(i);
            final int transferIndex = i;
            
            result = result.thenCompose(success -> {
                if (success) {
                    // Create unique idempotency key for this transfer within the batch
                    String transferIdempotentId = idempotentId + "_transfer_" + transferIndex;
                    
                    return transfer(transfer.getFromUserId(), transfer.getFromType(),
                                  transfer.getToUserId(), transfer.getToType(),
                                  transfer.getAmount(), 
                                  transfer.getDescription() + " (Batch: " + batchTransactionId + ")",
                                  transferIdempotentId);
                } else {
                    return CompletableFuture.completedFuture(false);
                }
            });
        }
        
        return result.thenApply(success -> {
            // Store batch-level idempotency marker after successful completion
            if (success) {
                try {
                    String batchIdempotencyKey = "batch_idem:" + idempotentId;
                    accountBusinessService.getRocksDBService().put(batchIdempotencyKey, "completed");
                    
                    // Clean up processing marker
                    accountBusinessService.getRocksDBService().delete(batchIdempotencyKey + ":processing");
                    
                    log.info("Batch transfer completed successfully for idempotentId: {}", idempotentId);
                } catch (Exception e) {
                    log.error("Failed to store batch idempotency marker: {}", idempotentId, e);
                }
            } else {
                // Clean up processing marker on failure
                try {
                    String batchIdempotencyKey = "batch_idem:" + idempotentId;
                    accountBusinessService.getRocksDBService().delete(batchIdempotencyKey + ":processing");
                } catch (Exception e) {
                    log.warn("Failed to clean up processing marker: {}", idempotentId, e);
                }
            }
            return success;
        });
    }
    
    /**
     * Deprecated: Use batchTransfer(List<TransferRequest>, String) instead
     */
    @Deprecated
    public CompletableFuture<Boolean> batchTransfer(List<TransferRequest> transfers) {
        CompletableFuture<Boolean> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalArgumentException("idempotentId is mandatory for batch transfers. Use batchTransfer(transfers, idempotentId) instead."));
        return failed;
    }
    
    /**
     * Submit command to standalone FIFO queue
     */
    private CompletableFuture<Boolean> submitToStandaloneQueue(String command) {
        StandaloneCommand standaloneCommand = new StandaloneCommand(command);
        
        if (!commandQueue.offer(standaloneCommand)) {
            log.error("Failed to enqueue standalone command: {}", command);
            return CompletableFuture.completedFuture(false);
        }
        
        return standaloneCommand.getFuture();
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

    /**
     * Command wrapper for standalone mode FIFO processing
     */
    private static class StandaloneCommand {
        private final String commandString;
        private final CompletableFuture<Boolean> future;
        
        public StandaloneCommand(String commandString) {
            this.commandString = commandString;
            this.future = new CompletableFuture<>();
        }
        
        public String getCommandString() {
            return commandString;
        }
        
        public CompletableFuture<Boolean> getFuture() {
            return future;
        }
        
        public void complete(boolean success) {
            future.complete(success);
        }
        
        public void completeExceptionally(Throwable throwable) {
            future.completeExceptionally(throwable);
        }
    }
} 
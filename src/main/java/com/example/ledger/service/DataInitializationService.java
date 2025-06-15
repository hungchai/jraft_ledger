package com.example.ledger.service;

import com.example.ledger.config.RocksDBService;
import com.example.ledger.mapper.AccountMapper;
import com.example.ledger.mapper.ProcessedTransactionMapper;
import com.example.ledger.model.Account;
import com.example.ledger.model.ProcessedTransaction;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Data Initialization Service
 * Loads data from MySQL into RocksDB on application startup
 */
@Slf4j
@Service
@Order(1) // Execute early in startup process
public class DataInitializationService implements CommandLineRunner {

    @Autowired
    private RocksDBService rocksDBService;

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private ProcessedTransactionMapper transactionMapper;

    @Autowired
    private AsyncMySQLBatchWriter asyncMySQLBatchWriter;

    @Autowired
    private ObjectMapper objectMapper;

    // RocksDB key prefixes
    private static final String ACCOUNT_PREFIX = "account:";
    private static final String TRANSACTION_PREFIX = "transaction:";
    private static final String INIT_FLAG_KEY = "system:initialized";

    @Override
    public void run(String... args) throws Exception {
        log.info("Forcing RocksDB initialization from MySQL on every startup");
        try {
            // Always initialize accounts and transactions from MySQL
            initializeAccounts();
            initializeTransactions();
            markAsInitialized();
            log.info("Data initialization completed successfully");
        } catch (Exception e) {
            log.error("Failed to initialize data from MySQL to RocksDB", e);
            throw e;
        }
    }

    /**
     * Load all accounts from MySQL and store in RocksDB
     */
    private void initializeAccounts() {
        try {
            log.info("Loading accounts from MySQL...");
            
            // Get all accounts from MySQL
            List<Account> accounts = accountMapper.selectList(null);
            log.info("Found {} accounts in MySQL", accounts.size());
            
            int loadedCount = 0;
            for (Account account : accounts) {
                try {
                    // Store account data
                    String accountKey = ACCOUNT_PREFIX + account.getAccountId();
                    String accountJson = objectMapper.writeValueAsString(account);
                    rocksDBService.put(accountKey, accountJson);
                    
                    // Use only accountId as the key for balance
                    String balanceKey = account.getAccountId();
                    String balanceValue = account.getBalance().toString();
                    rocksDBService.put(balanceKey, balanceValue);
                    
                    loadedCount++;
                    
                    log.debug("Loaded account: {} with balance: {}", 
                        account.getAccountId(), account.getBalance());
                        
                } catch (Exception e) {
                    log.error("Failed to load account: {}", account.getAccountId(), e);
                }
            }
            
            log.info("Successfully loaded {} accounts into RocksDB", loadedCount);
            
        } catch (Exception e) {
            log.error("Failed to initialize accounts", e);
            throw new RuntimeException("Account initialization failed", e);
        }
    }

    /**
     * Load all processed transactions from MySQL and store in RocksDB
     */
    private void initializeTransactions() {
        try {
            log.info("Loading transactions from MySQL...");
            
            // Get all transactions from MySQL
            List<ProcessedTransaction> transactions = transactionMapper.selectList(null);
            log.info("Found {} transactions in MySQL", transactions.size());
            
            int loadedCount = 0;
            for (ProcessedTransaction transaction : transactions) {
                try {
                    String transactionKey = TRANSACTION_PREFIX + transaction.getTransactionId();
                    String transactionJson = objectMapper.writeValueAsString(transaction);
                    rocksDBService.put(transactionKey, transactionJson);
                    
                    loadedCount++;
                    
                    log.debug("Loaded transaction: {} from {} to {} amount: {}", 
                        transaction.getTransactionId(),
                        transaction.getFromAccountId(),
                        transaction.getToAccountId(),
                        transaction.getAmount());
                        
                } catch (Exception e) {
                    log.error("Failed to load transaction: {}", transaction.getTransactionId(), e);
                }
            }
            
            log.info("Successfully loaded {} transactions into RocksDB", loadedCount);
            
        } catch (Exception e) {
            log.error("Failed to initialize transactions", e);
            throw new RuntimeException("Transaction initialization failed", e);
        }
    }

    /**
     * Mark RocksDB as initialized
     */
    private void markAsInitialized() {
        try {
            String timestamp = String.valueOf(System.currentTimeMillis());
            rocksDBService.put(INIT_FLAG_KEY, timestamp);
            log.info("Marked RocksDB as initialized at timestamp: {}", timestamp);
        } catch (Exception e) {
            log.error("Failed to mark as initialized", e);
        }
    }

    /**
     * Get account balance from RocksDB
     */
    public BigDecimal getAccountBalance(String accountId) {
        try {
            // Use only accountId as the key for balance
            String balanceValue = rocksDBService.get(accountId);
            return balanceValue != null ? new BigDecimal(balanceValue) : BigDecimal.ZERO;
        } catch (Exception e) {
            log.error("Failed to get balance for account: {}", accountId, e);
            return BigDecimal.ZERO;
        }
    }

    /**
     * Update account balance in RocksDB and enqueue for MySQL
     */
    public void updateAccountBalance(String accountId, BigDecimal newBalance) {
        try {
            // Use only accountId as the key for balance
            rocksDBService.put(accountId, newBalance.toString());
            log.debug("Updated balance for account {}: {}", accountId, newBalance);
            // Enqueue for MySQL
            asyncMySQLBatchWriter.enqueue(WriteEvent.forBalance(accountId, newBalance));
        } catch (Exception e) {
            log.error("Failed to update balance for account: {}", accountId, e);
            throw new RuntimeException("Failed to update account balance", e);
        }
    }

    /**
     * Get account data from RocksDB
     */
    public Account getAccount(String accountId) {
        try {
            String accountKey = ACCOUNT_PREFIX + accountId;
            String accountJson = rocksDBService.get(accountKey);
            if (accountJson != null) {
                return objectMapper.readValue(accountJson, Account.class);
            }
            return null;
        } catch (Exception e) {
            log.error("Failed to get account: {}", accountId, e);
            return null;
        }
    }

    /**
     * Store transaction in RocksDB and enqueue for MySQL
     */
    public void storeTransaction(ProcessedTransaction transaction) {
        try {
            String transactionKey = TRANSACTION_PREFIX + transaction.getTransactionId();
            String transactionJson = objectMapper.writeValueAsString(transaction);
            rocksDBService.put(transactionKey, transactionJson);
            log.debug("Stored transaction: {}", transaction.getTransactionId());
            // Enqueue for MySQL
            asyncMySQLBatchWriter.enqueue(WriteEvent.forTransaction(transaction));
        } catch (Exception e) {
            log.error("Failed to store transaction: {}", transaction.getTransactionId(), e);
            throw new RuntimeException("Failed to store transaction", e);
        }
    }

    /**
     * Force re-initialization (useful for testing or data refresh)
     */
    public void forceReinitialize() {
        try {
            log.info("Force re-initializing RocksDB from MySQL...");
            
            // Remove initialization flag
            rocksDBService.delete(INIT_FLAG_KEY.getBytes());
            
            // Re-run initialization
            run();
            
        } catch (Exception e) {
            log.error("Failed to force re-initialize", e);
            throw new RuntimeException("Force re-initialization failed", e);
        }
    }
} 
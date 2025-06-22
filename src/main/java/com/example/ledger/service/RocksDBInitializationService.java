package com.example.ledger.service;

import com.example.ledger.config.RocksDBService;
import com.example.ledger.mapper.AccountMapper;
import com.example.ledger.mapper.ProcessedTransactionMapper;
import com.example.ledger.model.Account;
import com.example.ledger.model.ProcessedTransaction;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class RocksDBInitializationService {
    @Autowired
    private RocksDBService rocksDBService;
    @Autowired
    private AccountMapper accountMapper;
    @Autowired
    private ProcessedTransactionMapper transactionMapper;
    @Autowired
    private ObjectMapper objectMapper;

    private static final String ACCOUNT_PREFIX = "account:";
    private static final String TRANSACTION_PREFIX = "transaction:";
    private static final String INIT_FLAG_KEY = "system:initialized";

    /**
     * Initialize RocksDB from MySQL data.
     * This method will throw exceptions if MySQL is down or data loading fails.
     */
    public void initializeFromMySQL() {
        log.info("Initializing RocksDB from MySQL...");
        loadAccounts();
        loadTransactions();
        rocksDBService.put(INIT_FLAG_KEY, String.valueOf(System.currentTimeMillis()));
        log.info("✅ RocksDB initialization completed.");
    }

    private void loadAccounts() {
        try {
            List<Account> accounts = accountMapper.selectList(null);
            log.info("Found {} accounts in MySQL", accounts.size());
            for (Account account : accounts) {
                try {
                    String accountKey = ACCOUNT_PREFIX + account.getAccountId();
                    String accountJson = objectMapper.writeValueAsString(account);
                    rocksDBService.put(accountKey, accountJson);
                    rocksDBService.put(account.getAccountId(), account.getBalance().toString());
                } catch (Exception e) {
                    log.error("Failed to load account: {}", account.getAccountId(), e);
                }
            }
        } catch (Exception e) {
            log.error("Failed to load accounts from MySQL", e);
            throw e; // Re-throw to trigger application exit
        }
    }

    private void loadTransactions() {
        try {
            List<ProcessedTransaction> transactions = transactionMapper.selectList(null);
            log.info("Found {} transactions in MySQL", transactions.size());
            for (ProcessedTransaction transaction : transactions) {
                try {
                    String transactionKey = TRANSACTION_PREFIX + transaction.getTransactionId();
                    String transactionJson = objectMapper.writeValueAsString(transaction);
                    rocksDBService.put(transactionKey, transactionJson);
                } catch (Exception e) {
                    log.error("Failed to load transaction: {}", transaction.getTransactionId(), e);
                }
            }
        } catch (Exception e) {
            log.error("Failed to load transactions from MySQL", e);
            throw e; // Re-throw to trigger application exit
        }
    }
} 
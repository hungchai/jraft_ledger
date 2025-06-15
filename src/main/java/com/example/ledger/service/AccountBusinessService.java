package com.example.ledger.service;

import com.example.ledger.config.RocksDBService;
import com.example.ledger.model.Account;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Slf4j
@Service
public class AccountBusinessService {
    @Autowired
    private RocksDBService rocksDBService;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String ACCOUNT_PREFIX = "account:";

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
     * Get account balance from RocksDB
     */
    public BigDecimal getAccountBalance(String accountId) {
        try {
            String balanceValue = rocksDBService.get(accountId);
            return balanceValue != null ? new BigDecimal(balanceValue) : BigDecimal.ZERO;
        } catch (Exception e) {
            log.error("Failed to get balance for account: {}", accountId, e);
            return BigDecimal.ZERO;
        }
    }

    /**
     * Update account balance in RocksDB
     */
    public void updateAccountBalance(String accountId, BigDecimal newBalance) {
        try {
            rocksDBService.put(accountId, newBalance.toString());
            log.debug("Updated balance for account {}: {}", accountId, newBalance);
        } catch (Exception e) {
            log.error("Failed to update balance for account: {}", accountId, e);
            throw new RuntimeException("Failed to update account balance", e);
        }
    }

    /**
     * Check if account exists in RocksDB
     */
    public boolean accountExists(String accountId) {
        return getAccount(accountId) != null;
    }
} 
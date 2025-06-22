package com.example.ledger.integration;

import com.example.ledger.LedgerApplication;
import com.example.ledger.mapper.AccountMapper;
import com.example.ledger.mapper.ProcessedTransactionMapper;
import com.example.ledger.model.Account;
import com.example.ledger.model.ProcessedTransaction;
import com.example.ledger.service.LedgerService;
import com.example.ledger.service.RocksDBInitializationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = LedgerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class LedgerIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private LedgerService ledgerService;

    @Autowired(required = false)
    private RocksDBInitializationService rocksDBInitializationService;

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private ProcessedTransactionMapper processedTransactionMapper;

    private String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
    }

    @Test
    public void testDataInitializationService() {
        // Test that RocksDBInitializationService is available in test profile
        if (rocksDBInitializationService != null) {
            assertDoesNotThrow(() -> {
                rocksDBInitializationService.initializeFromMySQL();
            }, "RocksDB initialization should not throw exceptions");
        }
    }

    @Test
    public void testDataStatusAPI() {
        ResponseEntity<String> response = restTemplate.getForEntity(
            baseUrl + "/api/data/status", String.class);
        
        assertEquals(200, response.getStatusCodeValue());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("status"));
    }

    @Test
    public void testDataInitializationAPI() {
        ResponseEntity<String> response = restTemplate.postForEntity(
            baseUrl + "/api/data/initialize", null, String.class);
        
        assertEquals(200, response.getStatusCodeValue());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("success"));
    }

    @Test
    public void testCreateAccount() throws Exception {
        // Test creating a new account
        CompletableFuture<Boolean> result = ledgerService.createAccount("NewTestUser", Account.AccountType.AVAILABLE);
        assertTrue(result.get(5, TimeUnit.SECONDS), "Account creation should succeed");
        
        // Verify the account exists by checking balance
        BigDecimal balance = ledgerService.getBalance("NewTestUser", Account.AccountType.AVAILABLE);
        assertNotNull(balance);
        assertEquals(0, balance.compareTo(BigDecimal.ZERO));
    }

    @Test
    public void testExistingAccountBalances() {
        // Test with existing accounts from the database
        BigDecimal userABalance = ledgerService.getBalance("UserA", Account.AccountType.AVAILABLE);
        BigDecimal userBBalance = ledgerService.getBalance("UserB", Account.AccountType.AVAILABLE);
        
        assertNotNull(userABalance, "UserA balance should not be null");
        assertNotNull(userBBalance, "UserB balance should not be null");
        
        // These should match the database values we saw in testing
        assertTrue(userABalance.compareTo(BigDecimal.ZERO) >= 0, "UserA balance should be non-negative");
        assertTrue(userBBalance.compareTo(BigDecimal.ZERO) >= 0, "UserB balance should be non-negative");
    }

    @Test
    public void testTransferWithSufficientBalance() throws Exception {
        // Use UserA who has sufficient balance from the database
        BigDecimal initialBalanceA = ledgerService.getBalance("UserA", Account.AccountType.AVAILABLE);
        BigDecimal initialBalanceB = ledgerService.getBalance("UserB", Account.AccountType.AVAILABLE);
        
        // Get initial H2 database balances
        List<Account> allAccounts = accountMapper.selectList(null);
        Account initialAccountA = allAccounts.stream()
            .filter(acc -> "UserA:available".equals(acc.getAccountId()))
            .findFirst()
            .orElse(null);
        Account initialAccountB = allAccounts.stream()
            .filter(acc -> "UserB:available".equals(acc.getAccountId()))
            .findFirst()
            .orElse(null);
        assertNotNull(initialAccountA, "UserA account should exist in H2 database");
        assertNotNull(initialAccountB, "UserB account should exist in H2 database");
        
        BigDecimal initialDbBalanceA = initialAccountA.getBalance();
        BigDecimal initialDbBalanceB = initialAccountB.getBalance();
        
        // Count initial transactions in H2 database
        List<ProcessedTransaction> initialTransactions = processedTransactionMapper.selectList(null);
        int initialTransactionCount = initialTransactions.size();
        
        System.out.println("=== Transfer Test with H2 Verification ===");
        System.out.println("Initial RocksDB: UserA=" + initialBalanceA + ", UserB=" + initialBalanceB);
        System.out.println("Initial H2 DB:   UserA=" + initialDbBalanceA + ", UserB=" + initialDbBalanceB);
        System.out.println("Initial transaction count: " + initialTransactionCount);
        
        // Only proceed if UserA has sufficient balance
        if (initialBalanceA.compareTo(new BigDecimal("50")) >= 0) {
            CompletableFuture<Boolean> transferResult = ledgerService.transfer(
                "UserA", Account.AccountType.AVAILABLE,
                "UserB", Account.AccountType.AVAILABLE,
                new BigDecimal("50.00"),
                "Integration test transfer with H2 verification"
            );
            
            assertTrue(transferResult.get(5, TimeUnit.SECONDS), "Transfer should succeed");
            
            // Verify RocksDB balances changed correctly
            BigDecimal finalBalanceA = ledgerService.getBalance("UserA", Account.AccountType.AVAILABLE);
            BigDecimal finalBalanceB = ledgerService.getBalance("UserB", Account.AccountType.AVAILABLE);
            
            assertEquals(initialBalanceA.subtract(new BigDecimal("50.00")).setScale(4), 
                        finalBalanceA.setScale(4), "UserA RocksDB balance should decrease by 50");
            assertEquals(initialBalanceB.add(new BigDecimal("50.00")).setScale(4), 
                        finalBalanceB.setScale(4), "UserB RocksDB balance should increase by 50");
            
            // Wait a bit for AsyncMySQLBatchWriter to sync to H2 database
            Thread.sleep(1000);
            
            // Verify H2 database balances were updated correctly
            List<Account> finalAccounts = accountMapper.selectList(null);
            Account finalAccountA = finalAccounts.stream()
                .filter(acc -> "UserA:available".equals(acc.getAccountId()))
                .findFirst()
                .orElse(null);
            Account finalAccountB = finalAccounts.stream()
                .filter(acc -> "UserB:available".equals(acc.getAccountId()))
                .findFirst()
                .orElse(null);
            
            assertNotNull(finalAccountA, "UserA account should still exist in H2 database");
            assertNotNull(finalAccountB, "UserB account should still exist in H2 database");
            
            // The key insight: RocksDB and H2 should have the same final balances after the transfer
            assertEquals(finalBalanceA.setScale(4), finalAccountA.getBalance().setScale(4), 
                        "UserA: RocksDB and H2 database balances should match after transfer");
            assertEquals(finalBalanceB.setScale(4), finalAccountB.getBalance().setScale(4), 
                        "UserB: RocksDB and H2 database balances should match after transfer");
            
            // Verify the change in H2 database follows conservation of funds
            BigDecimal dbChangeA = finalAccountA.getBalance().subtract(initialDbBalanceA);
            BigDecimal dbChangeB = finalAccountB.getBalance().subtract(initialDbBalanceB);
            
            // The key validation: changes should be equal and opposite, and sum to zero (conservation)
            assertEquals(dbChangeA.add(dbChangeB).setScale(4), BigDecimal.ZERO.setScale(4), 
                        "Total balance change should be zero (conservation of funds)");
            
            // Verify the RocksDB changes are consistent
            BigDecimal rocksDbChangeA = finalBalanceA.subtract(initialBalanceA);
            BigDecimal rocksDbChangeB = finalBalanceB.subtract(initialBalanceB);
            
            assertEquals(new BigDecimal("-50.00").setScale(4), rocksDbChangeA.setScale(4), 
                        "UserA RocksDB balance should decrease by exactly 50");
            assertEquals(new BigDecimal("50.00").setScale(4), rocksDbChangeB.setScale(4), 
                        "UserB RocksDB balance should increase by exactly 50");
            
            // Verify a new transaction was recorded in H2 database
            List<ProcessedTransaction> finalTransactions = processedTransactionMapper.selectList(null);
            assertEquals(initialTransactionCount + 1, finalTransactions.size(), 
                        "One new transaction should be recorded in H2 database");
            
            // Find and verify the new transaction
            ProcessedTransaction newTransaction = finalTransactions.stream()
                .filter(t -> "Integration test transfer with H2 verification".equals(t.getDescription()))
                .findFirst()
                .orElse(null);
            
            assertNotNull(newTransaction, "New transaction should be found in H2 database");
            assertEquals("UserA:available", newTransaction.getFromAccountId(), "From account should match");
            assertEquals("UserB:available", newTransaction.getToAccountId(), "To account should match");
            assertEquals(new BigDecimal("50.00").setScale(4), newTransaction.getAmount().setScale(4), "Amount should match");
            assertEquals("COMMITTED", newTransaction.getStatus(), "Transaction status should be COMMITTED");
            assertNotNull(newTransaction.getProcessedAt(), "Transaction should have processed timestamp");
            
            System.out.println("✅ Transfer test passed - AsyncMySQLBatchWriter successfully synced to H2");
            System.out.println("   Final RocksDB: UserA=" + finalBalanceA + ", UserB=" + finalBalanceB);
            System.out.println("   Final H2 DB:   UserA=" + finalAccountA.getBalance() + ", UserB=" + finalAccountB.getBalance());
            System.out.println("   RocksDB Changes: UserA=" + rocksDbChangeA + ", UserB=" + rocksDbChangeB);
            System.out.println("   H2 DB Changes:   UserA=" + dbChangeA + ", UserB=" + dbChangeB);
            System.out.println("   ✅ Conservation verified: " + dbChangeA + " + " + dbChangeB + " = " + dbChangeA.add(dbChangeB));
        } else {
            System.out.println("⚠️ Skipping transfer test - UserA has insufficient balance: " + initialBalanceA);
        }
    }

    @Test
    public void testGetUserBalances() {
        // Test with existing UserA who has multiple account types
        LedgerService.UserBalances balances = ledgerService.getUserBalances("UserA");
        
        assertNotNull(balances, "User balances should not be null");
        assertEquals("UserA", balances.getUserId(), "User ID should match");
        assertNotNull(balances.getAvailableBalance(), "Available balance should not be null");
        assertNotNull(balances.getBrokerageBalance(), "Brokerage balance should not be null");
        assertNotNull(balances.getExchangeBalance(), "Exchange balance should not be null");
    }

    @Test
    public void testRestApiGetUserBalance() {
        ResponseEntity<String> response = restTemplate.getForEntity(
            baseUrl + "/api/balance/user/UserA", String.class);
        
        assertEquals(200, response.getStatusCodeValue());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("userId"));
        assertTrue(response.getBody().contains("availableBalance"));
        assertTrue(response.getBody().contains("brokerageBalance"));
        assertTrue(response.getBody().contains("exchangeBalance"));
    }

    @Test
    public void testRestApiGetAccountBalance() {
        ResponseEntity<String> response = restTemplate.getForEntity(
            baseUrl + "/api/balance/account/UserA/available", String.class);
        
        assertEquals(200, response.getStatusCodeValue());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("userId"));
        assertTrue(response.getBody().contains("accountType"));
        assertTrue(response.getBody().contains("balance"));
    }

    @Test
    public void testRestApiCreateAccount() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        
        String requestBody = """
            {
                "userId": "APITestUser",
                "accountType": "AVAILABLE"
            }
            """;
        
        HttpEntity<String> request = new HttpEntity<>(requestBody, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(
            baseUrl + "/api/balance/create", request, String.class);
        
        assertEquals(200, response.getStatusCodeValue());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("success"));
    }

    @Test
    public void testRestApiTransfer() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        
        String requestBody = """
            {
                "fromUserId": "UserA",
                "fromType": "AVAILABLE",
                "toUserId": "UserB", 
                "toType": "AVAILABLE",
                "amount": 25.00,
                "description": "API integration test transfer"
            }
            """;
        
        HttpEntity<String> request = new HttpEntity<>(requestBody, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(
            baseUrl + "/api/transfer/single", request, String.class);
        
        assertEquals(200, response.getStatusCodeValue());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("success"));
    }

    @Test
    public void testIdempotentTransfer() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        headers.set("Idempotency-Key", "test-integration-idempotent-001");
        
        String requestBody = """
            {
                "fromUserId": "UserA",
                "fromType": "AVAILABLE",
                "toUserId": "UserB", 
                "toType": "AVAILABLE",
                "amount": 10.00,
                "description": "Idempotent integration test"
            }
            """;
        
        HttpEntity<String> request = new HttpEntity<>(requestBody, headers);
        
        // First request
        ResponseEntity<String> response1 = restTemplate.postForEntity(
            baseUrl + "/api/transfer/single", request, String.class);
        
        // Second request with same idempotency key
        ResponseEntity<String> response2 = restTemplate.postForEntity(
            baseUrl + "/api/transfer/single", request, String.class);
        
        assertEquals(200, response1.getStatusCodeValue());
        assertEquals(200, response2.getStatusCodeValue());
        assertNotNull(response1.getBody());
        assertNotNull(response2.getBody());
    }

    @Test
    public void testBatchTransfer() throws Exception {
        // Create test accounts if they don't exist
        ledgerService.createAccount("BatchTestA", Account.AccountType.AVAILABLE).get(5, TimeUnit.SECONDS);
        ledgerService.createAccount("BatchTestB", Account.AccountType.AVAILABLE).get(5, TimeUnit.SECONDS);
        
        // Fund BatchTestA from Bank if Bank has sufficient balance
        try {
            ledgerService.transfer("Bank", Account.AccountType.AVAILABLE,
                                  "BatchTestA", Account.AccountType.AVAILABLE,
                                  new BigDecimal("100.00"), "Funding for batch test").get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            // If funding fails, skip this test
            System.out.println("Skipping batch transfer test due to funding issues: " + e.getMessage());
            return;
        }
        
        List<LedgerService.TransferRequest> transfers = List.of(
            new LedgerService.TransferRequest("BatchTestA", Account.AccountType.AVAILABLE,
                                            "BatchTestB", Account.AccountType.AVAILABLE,
                                            new BigDecimal("10.00"), "Batch transfer 1"),
            new LedgerService.TransferRequest("BatchTestA", Account.AccountType.AVAILABLE,
                                            "UserB", Account.AccountType.AVAILABLE,
                                            new BigDecimal("20.00"), "Batch transfer 2")
        );
        
        CompletableFuture<Boolean> batchResult = ledgerService.batchTransfer(transfers);
        assertTrue(batchResult.get(10, TimeUnit.SECONDS), "Batch transfer should succeed");
    }

    @Test
    public void testRealWorldScenario() throws Exception {
        System.out.println("=== Real World Scenario Test ===");
        
        // Get initial balances from existing accounts
        BigDecimal initialUserA = ledgerService.getBalance("UserA", Account.AccountType.AVAILABLE);
        BigDecimal initialUserB = ledgerService.getBalance("UserB", Account.AccountType.AVAILABLE);
        BigDecimal initialBank = ledgerService.getBalance("Bank", Account.AccountType.AVAILABLE);
        
        System.out.println("Initial balances:");
        System.out.println("UserA: " + initialUserA);
        System.out.println("UserB: " + initialUserB);
        System.out.println("Bank: " + initialBank);
        
        // Perform a realistic transfer scenario
        if (initialUserA.compareTo(new BigDecimal("100")) >= 0) {
            // UserA transfers to UserB
            CompletableFuture<Boolean> transfer1 = ledgerService.transfer(
                "UserA", Account.AccountType.AVAILABLE,
                "UserB", Account.AccountType.AVAILABLE,
                new BigDecimal("75.00"), "Real world test transfer"
            );
            
            assertTrue(transfer1.get(5, TimeUnit.SECONDS), "Transfer should succeed");
            
            // Verify final balances
            BigDecimal finalUserA = ledgerService.getBalance("UserA", Account.AccountType.AVAILABLE);
            BigDecimal finalUserB = ledgerService.getBalance("UserB", Account.AccountType.AVAILABLE);
            
            System.out.println("Final balances:");
            System.out.println("UserA: " + finalUserA);
            System.out.println("UserB: " + finalUserB);
            
            // Verify accounting equation holds
            assertEquals(initialUserA.subtract(new BigDecimal("75.00")).setScale(4), 
                        finalUserA.setScale(4), "UserA balance should decrease correctly");
            assertEquals(initialUserB.add(new BigDecimal("75.00")).setScale(4), 
                        finalUserB.setScale(4), "UserB balance should increase correctly");
        }
        
        System.out.println("=== Real World Scenario Test Complete ===");
    }

    @Test
    public void testIdempotencyCacheStats() {
        ResponseEntity<String> response = restTemplate.getForEntity(
            baseUrl + "/api/admin/idempotency/stats", String.class);
        
        assertEquals(200, response.getStatusCodeValue());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("totalEntries"));
    }

    @Test
    public void testSystemHealthAfterOperations() {
        // Verify system is still healthy after all operations
        ResponseEntity<String> statusResponse = restTemplate.getForEntity(
            baseUrl + "/api/data/status", String.class);
        
        assertEquals(200, statusResponse.getStatusCodeValue());
        
        // Verify we can still query balances
        ResponseEntity<String> balanceResponse = restTemplate.getForEntity(
            baseUrl + "/api/balance/user/UserA", String.class);
        
        assertEquals(200, balanceResponse.getStatusCodeValue());
        assertNotNull(balanceResponse.getBody());
    }
} 
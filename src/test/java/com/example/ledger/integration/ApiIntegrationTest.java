package com.example.ledger.integration;

import com.example.ledger.model.Account;
import com.example.ledger.repository.AccountRepository;
import com.example.ledger.repository.TransactionRepository;
import com.example.ledger.repository.JournalEntryRepository;
import com.example.ledger.service.RaftService;
import com.example.ledger.dto.AccountResponse;
import com.example.ledger.dto.TransferRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class ApiIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    private String baseUrl;

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        public RaftService raftService() {
            RaftService mockService = Mockito.mock(RaftService.class);
            Mockito.when(mockService.isLeader()).thenReturn(true);
            Mockito.when(mockService.submitTransfer(Mockito.any())).thenReturn(CompletableFuture.completedFuture(true));
            Mockito.when(mockService.submitBatchTransfer(Mockito.any())).thenReturn(CompletableFuture.completedFuture(true));
            return mockService;
        }
    }

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port + "/api/ledger";
        journalEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void testCreateAndGetAccount() {
        Account account = new Account();
        account.setAccountNumber("ACC001");
        account.setAccountName("Test Account");
        account.setAccountType(Account.AccountType.ASSET);
        account.setBalance(new BigDecimal("1000.00"));

        ResponseEntity<Void> createResponse = restTemplate.postForEntity(baseUrl + "/accounts", account, Void.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<AccountResponse> getResponse = restTemplate.getForEntity(baseUrl + "/accounts/ACC001", AccountResponse.class);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        AccountResponse body = getResponse.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getAccountNumber()).isEqualTo("ACC001");
        assertThat(body.getAccountName()).isEqualTo("Test Account");
        assertThat(new BigDecimal(body.getBalance())).isEqualByComparingTo("1000.00");
    }

    @Test
    void testCreateAccount_Idempotency() {
        Account account = new Account();
        account.setAccountNumber("ACC002");
        account.setAccountName("Idempotent Account");
        account.setAccountType(Account.AccountType.LIABILITY);
        account.setBalance(new BigDecimal("2000.00"));

        ResponseEntity<Void> response1 = restTemplate.postForEntity(baseUrl + "/accounts", account, Void.class);
        assertThat(response1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(accountRepository.count()).isEqualTo(1);

        ResponseEntity<Map> response2 = restTemplate.postForEntity(baseUrl + "/accounts", account, Map.class);
        assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response2.getBody()).containsEntry("error", "Account with this number already exists");
        assertThat(accountRepository.count()).isEqualTo(1);
    }

    @Test
    void testGetAllAccounts() {
        Account account1 = new Account();
        account1.setAccountNumber("ACC003");
        account1.setAccountName("Account 3");
        account1.setAccountType(Account.AccountType.ASSET);
        account1.setBalance(new BigDecimal("100.00"));
        restTemplate.postForEntity(baseUrl + "/accounts", account1, Void.class);

        Account account2 = new Account();
        account2.setAccountNumber("ACC004");
        account2.setAccountName("Account 4");
        account2.setAccountType(Account.AccountType.ASSET);
        account2.setBalance(new BigDecimal("200.00"));
        restTemplate.postForEntity(baseUrl + "/accounts", account2, Void.class);

        ResponseEntity<List> response = restTemplate.getForEntity(baseUrl + "/accounts", List.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().size()).isEqualTo(2);
    }

    @Test
    void testTransfer_SuccessAndIdempotency() {
        createAccount("ACC_SRC_1", "1000.00");
        createAccount("ACC_DST_1", "500.00");

        TransferRequest request = new TransferRequest();
        request.setFromAccountNumber("ACC_SRC_1");
        request.setToAccountNumber("ACC_DST_1");
        request.setAmount(new BigDecimal("200.00"));
        request.setReferenceId(UUID.randomUUID().toString());
        request.setDescription("Idempotent Transfer");

        ResponseEntity<Void> response1 = restTemplate.postForEntity(baseUrl + "/transfer", request, Void.class);
        assertThat(response1.getStatusCode()).isEqualTo(HttpStatus.OK);

        verifyAccountBalance("ACC_SRC_1", "800.00");
        verifyAccountBalance("ACC_DST_1", "700.00");
        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(journalEntryRepository.count()).isEqualTo(2);

        ResponseEntity<Map> response2 = restTemplate.postForEntity(baseUrl + "/transfer", request, Map.class);
        assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response2.getBody()).containsEntry("error", "Transaction with this reference ID already exists");

        verifyAccountBalance("ACC_SRC_1", "800.00");
        verifyAccountBalance("ACC_DST_1", "700.00");
        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(journalEntryRepository.count()).isEqualTo(2);
    }
    
    @Test
    void testTransfer_InsufficientFunds() {
        createAccount("ACC_SRC_2", "100.00");
        createAccount("ACC_DST_2", "500.00");

        TransferRequest request = new TransferRequest();
        request.setFromAccountNumber("ACC_SRC_2");
        request.setToAccountNumber("ACC_DST_2");
        request.setAmount(new BigDecimal("200.00"));
        request.setReferenceId(UUID.randomUUID().toString());
        request.setDescription("Transfer attempt");

        ResponseEntity<Map> response = restTemplate.postForEntity(baseUrl + "/transfer", request, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "Insufficient funds");

        verifyAccountBalance("ACC_SRC_2", "100.00");
        verifyAccountBalance("ACC_DST_2", "500.00");
    }

    @Test
    void testBatchTransfer_SuccessAndIdempotency() {
        createAccount("ACC_BATCH_1", "2000.00");
        createAccount("ACC_BATCH_2", "1000.00");
        createAccount("ACC_BATCH_3", "500.00");
        
        TransferRequest request1 = new TransferRequest();
        request1.setFromAccountNumber("ACC_BATCH_1");
        request1.setToAccountNumber("ACC_BATCH_2");
        request1.setAmount(new BigDecimal("300.00"));
        request1.setReferenceId(UUID.randomUUID().toString());
        request1.setDescription("Batch transfer 1");

        TransferRequest request2 = new TransferRequest();
        request2.setFromAccountNumber("ACC_BATCH_2");
        request2.setToAccountNumber("ACC_BATCH_3");
        request2.setAmount(new BigDecimal("400.00"));
        request2.setReferenceId(UUID.randomUUID().toString());
        request2.setDescription("Batch transfer 2");

        TransferRequest[] batch = {request1, request2};

        ResponseEntity<Void> response1 = restTemplate.postForEntity(baseUrl + "/batch-transfer", batch, Void.class);
        assertThat(response1.getStatusCode()).isEqualTo(HttpStatus.OK);

        verifyAccountBalance("ACC_BATCH_1", "1700.00");
        verifyAccountBalance("ACC_BATCH_2", "900.00");
        verifyAccountBalance("ACC_BATCH_3", "900.00");
        assertThat(transactionRepository.count()).isEqualTo(2);
        assertThat(journalEntryRepository.count()).isEqualTo(4);

        // Second call should be idempotent (duplicate reference ID should be rejected)
        ResponseEntity<String> response2 = restTemplate.postForEntity(baseUrl + "/batch-transfer", batch, String.class);
        assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response2.getBody()).contains("Transaction with this reference ID already exists");

        // Verify balances haven't changed from the first successful batch
        verifyAccountBalance("ACC_BATCH_1", "1700.00");
        verifyAccountBalance("ACC_BATCH_2", "900.00");
        verifyAccountBalance("ACC_BATCH_3", "900.00");
        assertThat(transactionRepository.count()).isEqualTo(2);
        assertThat(journalEntryRepository.count()).isEqualTo(4);
    }

    private void createAccount(String accountNumber, String balance) {
        Account account = new Account();
        account.setAccountNumber(accountNumber);
        account.setAccountName("Test Account " + accountNumber);
        account.setAccountType(Account.AccountType.ASSET);
        account.setBalance(new BigDecimal(balance));
        restTemplate.postForEntity(baseUrl + "/accounts", account, Void.class);
    }

    private void verifyAccountBalance(String accountNumber, String expectedBalance) {
        ResponseEntity<AccountResponse> response = restTemplate.getForEntity(baseUrl + "/accounts/" + accountNumber, AccountResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(new BigDecimal(response.getBody().getBalance())).isEqualByComparingTo(expectedBalance);
    }
} 
package com.tomma8.ledger.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tomma8.ledger.domain.model.AccountBalanceKey;
import com.tomma8.ledger.domain.model.BalanceEntry;
import com.tomma8.ledger.store.BalanceStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
        "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration," +
        "org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.MethodName.class)
@DisplayName("REST API Integration Tests")
class RestApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private BalanceStore balanceStore;

    @Test
    @DisplayName("Health check returns UP")
    void health() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("Create account via REST")
    void createAccount() throws Exception {
        String body = """
        {
            "requestId": "rest-001",
            "accountId": "ACC_REST_001",
            "accountType": "CLIENT",
            "displayName": "REST Account",
            "ownerId": "CUST-REST-001",
            "balanceInitializations": [
                {"balanceType": "AVAILABLE_BALANCE", "currency": "USD"}
            ]
        }
        """;

        mockMvc.perform(post("/ledger/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("Create posting via REST")
    void createPosting() throws Exception {
        createTestAccount("ACC_POST_001");
        createTestAccount("ACC_POST_002");
        seedBalance("ACC_POST_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("1000.00"));
        seedBalance("ACC_POST_002", "AVAILABLE_BALANCE", "USD", new BigDecimal("1000.00"));

        String body = """
        {
            "requestId": "rest-post-001",
            "businessEventType": "TEST",
            "businessEventRef": "REST-TEST-001",
            "valueDate": "2026-05-18",
            "legs": [
                {
                    "legId": "leg-001",
                    "postingType": "TEST",
                    "amount": "500.00",
                    "currency": "USD",
                    "lines": [
                        {
                            "accountId": "ACC_POST_001",
                            "balanceType": "AVAILABLE_BALANCE",
                            "entryType": "CREDIT",
                            "description": "REST credit"
                        },
                        {
                            "accountId": "ACC_POST_002",
                            "balanceType": "AVAILABLE_BALANCE",
                            "entryType": "DEBIT",
                            "description": "REST debit"
                        }
                    ]
                }
            ]
        }
        """;

        mockMvc.perform(post("/ledger/postings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.journalId").isNotEmpty());
    }

    @Test
    @DisplayName("Posting with insufficient balance returns 400")
    void postingInsufficientBalance() throws Exception {
        createTestAccount("ACC_INSF_001");
        createTestAccount("ACC_INSF_002");
        seedBalance("ACC_INSF_002", "AVAILABLE_BALANCE", "USD", new BigDecimal("10000.00"));

        String body = """
        {
            "requestId": "rest-insf-001",
            "businessEventType": "TEST",
            "businessEventRef": "REST-INSF-001",
            "valueDate": "2026-05-18",
            "legs": [
                {
                    "legId": "leg-001",
                    "postingType": "TEST",
                    "amount": "9999.00",
                    "currency": "USD",
                    "lines": [
                        {
                            "accountId": "ACC_INSF_001",
                            "balanceType": "AVAILABLE_BALANCE",
                            "entryType": "DEBIT",
                            "description": "Too much"
                        },
                        {
                            "accountId": "ACC_INSF_002",
                            "balanceType": "AVAILABLE_BALANCE",
                            "entryType": "CREDIT",
                            "description": "Counterparty"
                        }
                    ]
                }
            ]
        }
        """;

        mockMvc.perform(post("/ledger/postings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.errorCodes[0]").value("INSUFFICIENT_BALANCE"));
    }

    @Test
    @DisplayName("Balance query via REST")
    void balanceQuery() throws Exception {
        createTestAccount("ACC_BAL_001");

        mockMvc.perform(get("/ledger/balances")
                        .param("accountId", "ACC_BAL_001")
                        .param("balanceType", "AVAILABLE_BALANCE")
                        .param("currency", "USD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").isNumber())
                .andExpect(jsonPath("$.dataSource").value("STATE_MACHINE"));
    }

    @Test
    @DisplayName("Journal query by requestId via REST")
    void journalQueryByRequestId() throws Exception {
        createTestAccount("ACC_JNL_001");
        createTestAccount("ACC_JNL_002");
        seedBalance("ACC_JNL_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("1000.00"));
        seedBalance("ACC_JNL_002", "AVAILABLE_BALANCE", "USD", new BigDecimal("1000.00"));

        String postBody = """
        {
            "requestId": "rest-jnl-req-001",
            "businessEventType": "TEST",
            "businessEventRef": "REST-JNL-001",
            "valueDate": "2026-05-18",
            "legs": [
                {
                    "legId": "leg-001",
                    "postingType": "TEST",
                    "amount": "100.00",
                    "currency": "USD",
                    "lines": [
                        {
                            "accountId": "ACC_JNL_001",
                            "balanceType": "AVAILABLE_BALANCE",
                            "entryType": "CREDIT",
                            "description": "Journal test"
                        },
                        {
                            "accountId": "ACC_JNL_002",
                            "balanceType": "AVAILABLE_BALANCE",
                            "entryType": "DEBIT",
                            "description": "Journal test debit"
                        }
                    ]
                }
            ]
        }
        """;

        mockMvc.perform(post("/ledger/postings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postBody))
                .andExpect(status().isOk());

        mockMvc.perform(get("/ledger/journals/by-request-id")
                        .param("requestId", "rest-jnl-req-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("rest-jnl-req-001"));
    }

    @Test
    @DisplayName("Reversal via REST")
    void reversal() throws Exception {
        createTestAccount("ACC_REV_001");
        createTestAccount("ACC_REV_002");
        seedBalance("ACC_REV_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("1000.00"));
        seedBalance("ACC_REV_002", "AVAILABLE_BALANCE", "USD", new BigDecimal("1000.00"));

        // Create a posting first
        String postBody = """
        {
            "requestId": "rest-rev-post",
            "businessEventType": "TEST",
            "businessEventRef": "REST-REV-001",
            "valueDate": "2026-05-18",
            "legs": [
                {
                    "legId": "leg-001",
                    "postingType": "TEST",
                    "amount": "200.00",
                    "currency": "USD",
                    "lines": [
                        {
                            "accountId": "ACC_REV_001",
                            "balanceType": "AVAILABLE_BALANCE",
                            "entryType": "CREDIT",
                            "description": "To reverse"
                        },
                        {
                            "accountId": "ACC_REV_002",
                            "balanceType": "AVAILABLE_BALANCE",
                            "entryType": "DEBIT",
                            "description": "To reverse debit"
                        }
                    ]
                }
            ]
        }
        """;

        String postResponse = mockMvc.perform(post("/ledger/postings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String journalId = objectMapper.readTree(postResponse).get("journalId").asText();

        String revBody = """
        {
            "requestId": "rest-rev-001",
            "reversalReason": "Test reversal",
            "reversalReasonCode": "CANCELLATION",
            "valueDate": "2026-05-18"
        }
        """;

        mockMvc.perform(post("/ledger/journals/{journalId}/reversal", journalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("Account freeze and unfreeze via REST")
    void accountFreezeUnfreeze() throws Exception {
        createTestAccount("ACC_FREEZE_001");

        mockMvc.perform(post("/ledger/accounts/ACC_FREEZE_001/freeze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\": \"rest-freeze-001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        mockMvc.perform(post("/ledger/accounts/ACC_FREEZE_001/unfreeze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\": \"rest-unfreeze-001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("Manual adjustment draft create and approve via REST")
    void adjustmentDraftApprove() throws Exception {
        createTestAccount("ACC_ADJ_001");

        String draftBody = """
        {
            "requestId": "rest-adj-001",
            "makerId": "maker-001",
            "legs": [
                {
                    "legId": "leg-001",
                    "postingType": "ADJUSTMENT",
                    "amount": "300.00",
                    "currency": "USD",
                    "lines": [
                        {
                            "accountId": "ACC_ADJ_001",
                            "balanceType": "AVAILABLE_BALANCE",
                            "entryType": "CREDIT",
                            "description": "Adjustment credit"
                        },
                        {
                            "accountId": "ACC_ADJ_002",
                            "balanceType": "AVAILABLE_BALANCE",
                            "entryType": "DEBIT",
                            "description": "Adjustment debit"
                        }
                    ]
                }
            ]
        }
        """;

        // Need a second account for balanced adjustment
        mockMvc.perform(post("/ledger/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "requestId": "rest-adj-002",
                            "accountId": "ACC_ADJ_002",
                            "accountType": "COMPANY",
                            "displayName": "Adj Company",
                            "balanceInitializations": [
                                {"balanceType": "AVAILABLE_BALANCE", "currency": "USD"}
                            ]
                        }
                        """))
                .andExpect(status().isOk());

        String draftResponse = mockMvc.perform(post("/ledger/adjustments/drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draftBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andReturn().getResponse().getContentAsString();

        String draftId = objectMapper.readTree(draftResponse).get("draftId").asText();

        // Give ACC_ADJ_002 enough balance for the DEBIT
        balanceStore.put(new AccountBalanceKey("ACC_ADJ_002", "AVAILABLE_BALANCE", "CURRENT", "USD"),
                new BalanceEntry(new BigDecimal("1000.00"), 0, 1, "", Instant.now()));

        mockMvc.perform(post("/ledger/adjustments/drafts/{draftId}/approve", draftId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"checkerId\": \"checker-001\", \"approveRequestId\": \"appr-001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("EOD trigger via REST")
    void eodTrigger() throws Exception {
        mockMvc.perform(post("/ledger/periods/eod")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\": \"2026-05-17\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    private void seedBalance(String accountId, String balanceType, String currency, BigDecimal amount) {
        balanceStore.put(new AccountBalanceKey(accountId, balanceType, "CURRENT", currency),
                new BalanceEntry(amount, 0, 1, "", Instant.now()));
    }

    private void createTestAccount(String accountId) throws Exception {
        String body = String.format("""
        {
            "requestId": "rest-create-%s",
            "accountId": "%s",
            "accountType": "CLIENT",
            "displayName": "%s",
            "ownerId": "CUST-%s",
            "balanceInitializations": [
                {"balanceType": "AVAILABLE_BALANCE", "currency": "USD"}
            ]
        }
        """, accountId, accountId, accountId, accountId);

        mockMvc.perform(post("/ledger/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }
}

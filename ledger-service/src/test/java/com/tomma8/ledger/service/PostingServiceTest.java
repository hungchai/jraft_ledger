package com.tomma8.ledger.service;

import com.tomma8.ledger.domain.command.CommandResult;
import com.tomma8.ledger.domain.command.PostingCommand;
import com.tomma8.ledger.domain.model.*;
import com.tomma8.ledger.statemachine.LedgerStateMachine;
import com.tomma8.ledger.store.AccountMetaStore;
import com.tomma8.ledger.store.BalanceStore;
import com.tomma8.ledger.store.BalanceTypeConfigStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("PostingService (F-002)")
class PostingServiceTest {

    private BalanceStore balanceStore;
    private AccountMetaStore accountMetaStore;
    private BalanceTypeConfigStore balanceTypeConfigStore;
    private LedgerStateMachine stateMachine;
    private PostingService postingService;

    @BeforeEach
    void setUp() {
        balanceStore = new BalanceStore();
        accountMetaStore = new AccountMetaStore();
        balanceTypeConfigStore = new BalanceTypeConfigStore();
        stateMachine = new LedgerStateMachine(balanceStore, accountMetaStore, balanceTypeConfigStore);
        postingService = new PostingService(stateMachine);

        balanceTypeConfigStore.put("AVAILABLE_BALANCE", new BalanceTypeConfig(
                "AVAILABLE_BALANCE", false, null, SignConvention.NORMAL_CREDIT, 1));

        accountMetaStore.put("CLIENT_ACC_001", new Account(
                "CLIENT_ACC_001", AccountType.CLIENT, "Client",
                "CUST-001", AccountStatus.ACTIVE, null, Instant.now()));
        accountMetaStore.put("COMPANY_FX_ACC", new Account(
                "COMPANY_FX_ACC", AccountType.COMPANY, "Company FX",
                null, AccountStatus.ACTIVE, null, Instant.now()));
    }

    private void setBalance(String accountId, String type, String ccy, BigDecimal amount) {
        balanceStore.put(new AccountBalanceKey(accountId, type, "CURRENT", ccy),
                new BalanceEntry(amount, 0, 1, "", Instant.now()));
    }

    @Test
    @DisplayName("TC-F002-01 post valid single leg returns completed result")
    void post_validSingleLeg_returnsCompletedResult() {
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("1000.00"));
        setBalance("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD", new BigDecimal("5000.00"));

        PostingCommand cmd = new PostingCommand(
                "req-001", "RFQ_SETTLEMENT", "RFQ-001", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TRADE_SETTLEMENT", BigDecimal.ONE, "USD", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", EntryType.DEBIT, "Client pay"),
                        new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "CURRENT", EntryType.CREDIT, "Company receive")
                )))
        );

        CommandResult result = postingService.post(cmd);

        assertThat(result.isCompleted()).isTrue();
        assertThat(result.journalId()).isNotNull();
    }

    @Test
    @DisplayName("TC-F002-02 post insufficient balance returns rejected result")
    void post_insufficientBalance_returnsRejectedResult() {
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("100.00"));

        PostingCommand cmd = new PostingCommand(
                "req-002", "WITHDRAWAL", "WD-001", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "WITHDRAWAL", new BigDecimal("200.00"), "USD", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", EntryType.DEBIT, "Withdraw"),
                        new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "CURRENT", EntryType.CREDIT, "Settle")
                )))
        );

        CommandResult result = postingService.post(cmd);

        assertThat(result.isRejected()).isTrue();
        assertThat(result.errorCodes()).contains("INSUFFICIENT_BALANCE");
    }

    @Test
    @DisplayName("TC-F002-03 post unknown account returns rejected result")
    void post_unknownAccount_returnsRejectedResult() {
        PostingCommand cmd = new PostingCommand(
                "req-003", "TEST", "test-ref", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST", BigDecimal.ONE, "USD", List.of(
                        new PostingCommand.Line("GHOST_ACC", "AVAILABLE_BALANCE", "CURRENT", EntryType.DEBIT, "Ghost"),
                        new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "CURRENT", EntryType.CREDIT, "Company")
                )))
        );

        CommandResult result = postingService.post(cmd);

        assertThat(result.isRejected()).isTrue();
        assertThat(result.errorCodes()).contains("ACCOUNT_NOT_FOUND");
    }

    @Test
    @DisplayName("TC-F002-04 post same requestId twice returns idempotent result")
    void post_sameRequestIdTwice_idempotentResult() {
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("1000.00"));
        setBalance("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD", new BigDecimal("5000.00"));

        PostingCommand cmd = new PostingCommand(
                "req-abc", "TEST", "test-ref", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST", new BigDecimal("100.00"), "USD", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", EntryType.DEBIT, "Debit"),
                        new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "CURRENT", EntryType.CREDIT, "Credit")
                )))
        );

        CommandResult first = postingService.post(cmd);
        CommandResult second = postingService.post(cmd);

        assertThat(first.isCompleted()).isTrue();
        assertThat(second).isEqualTo(first);

        // Balance unchanged by second call
        AccountBalanceKey key = new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", "USD");
        assertThat(balanceStore.getOrThrow(key).amount()).isEqualByComparingTo(new BigDecimal("900.00"));
    }

    @Test
    @DisplayName("TC-F002-05 two-line leg with insufficient balance rejected")
    void post_unbalancedJournal_returnsBadRequest() {
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("50.00"));

        PostingCommand cmd = new PostingCommand(
                "req-005", "TEST", "test-ref", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST", new BigDecimal("100.00"), "USD", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", EntryType.DEBIT, "Debit"),
                        new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "CURRENT", EntryType.CREDIT, "Credit")
                )))
        );

        CommandResult result = postingService.post(cmd);

        assertThat(result.isRejected()).isTrue();
        assertThat(result.errorCodes()).contains("INSUFFICIENT_BALANCE");
    }

    @Test
    @DisplayName("TC-F002-06 post RFQ scenario two accounts atomic update")
    void post_rfqScenario_twoAccounts_atomicUpdate() {
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("1000.00"));
        setBalance("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD", new BigDecimal("5000.00"));

        PostingCommand cmd = new PostingCommand(
                "req-006", "RFQ_SETTLEMENT", "RFQ-002", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TRADE_SETTLEMENT", new BigDecimal("800.00"), "USD", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", EntryType.DEBIT, "Client"),
                        new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "CURRENT", EntryType.CREDIT, "Company")
                )))
        );

        CommandResult result = postingService.post(cmd);

        assertThat(result.isCompleted()).isTrue();
        Journal journal = stateMachine.getJournal(result.journalId());
        assertThat(journal.lines()).hasSize(2);

        AccountBalanceKey clientKey = new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", "USD");
        AccountBalanceKey companyKey = new AccountBalanceKey("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "CURRENT", "USD");
        assertThat(balanceStore.getOrThrow(clientKey).amount()).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(balanceStore.getOrThrow(companyKey).amount()).isEqualByComparingTo(new BigDecimal("5800.00"));
    }

    @Test
    @DisplayName("TC-F002-07 post frozen account returns rejected result")
    void post_frozenAccount_returnsRejectedResult() {
        accountMetaStore.put("CLIENT_ACC_001",
                accountMetaStore.getOrThrow("CLIENT_ACC_001").withStatus(AccountStatus.FROZEN));
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("1000.00"));

        PostingCommand cmd = new PostingCommand(
                "req-007", "TEST", "test-ref", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST", BigDecimal.ONE, "USD", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", EntryType.DEBIT, "Debit"),
                        new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "CURRENT", EntryType.CREDIT, "Credit")
                )))
        );

        CommandResult result = postingService.post(cmd);

        assertThat(result.isRejected()).isTrue();
        assertThat(result.errorCodes()).contains("ACCOUNT_FROZEN");
    }
}

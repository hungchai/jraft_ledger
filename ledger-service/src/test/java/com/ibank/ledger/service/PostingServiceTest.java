package com.ibank.ledger.service;

import com.ibank.ledger.domain.command.CommandResult;
import com.ibank.ledger.domain.command.PostingCommand;
import com.ibank.ledger.domain.model.*;
import com.ibank.ledger.statemachine.LedgerStateMachine;
import com.ibank.ledger.store.AccountMetaStore;
import com.ibank.ledger.store.BalanceStore;
import com.ibank.ledger.store.BalanceTypeConfigStore;
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
        balanceStore.put(new AccountBalanceKey(accountId, type, ccy),
                new BalanceEntry(amount, 0, 1, "", Instant.now()));
    }

    @Test
    @DisplayName("TC-F002-01 post valid single leg returns completed result")
    void post_validSingleLeg_returnsCompletedResult() {
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("1000.00"));
        setBalance("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD", new BigDecimal("5000.00"));

        PostingCommand cmd = new PostingCommand(
                "req-001", "RFQ_SETTLEMENT", "RFQ-001", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TRADE_SETTLEMENT", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.DEBIT, new BigDecimal("800.00"), "Client pay"),
                        new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD",
                                EntryType.CREDIT, new BigDecimal("800.00"), "Company receive")
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
                List.of(new PostingCommand.Leg("leg-1", "WITHDRAWAL", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.DEBIT, new BigDecimal("500.00"), "Withdraw"),
                        new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD",
                                EntryType.CREDIT, new BigDecimal("500.00"), "Settle")
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
                List.of(new PostingCommand.Leg("leg-1", "TEST", List.of(
                        new PostingCommand.Line("GHOST_ACC", "AVAILABLE_BALANCE", "USD",
                                EntryType.DEBIT, new BigDecimal("100.00"), "Ghost"),
                        new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD",
                                EntryType.CREDIT, new BigDecimal("100.00"), "Company")
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
                List.of(new PostingCommand.Leg("leg-1", "TEST", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.DEBIT, new BigDecimal("100.00"), "Debit"),
                        new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD",
                                EntryType.CREDIT, new BigDecimal("100.00"), "Credit")
                )))
        );

        CommandResult first = postingService.post(cmd);
        CommandResult second = postingService.post(cmd);

        assertThat(first.isCompleted()).isTrue();
        assertThat(second).isEqualTo(first);

        // Balance unchanged by second call
        AccountBalanceKey key = new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD");
        assertThat(balanceStore.getOrThrow(key).amount()).isEqualByComparingTo(new BigDecimal("900.00"));
    }

    @Test
    @DisplayName("TC-F002-05 post unbalanced journal returns bad request")
    void post_unbalancedJournal_returnsBadRequest() {
        PostingCommand cmd = new PostingCommand(
                "req-005", "TEST", "test-ref", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.DEBIT, new BigDecimal("100.00"), "Debit"),
                        new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD",
                                EntryType.CREDIT, new BigDecimal("99.00"), "Credit")
                )))
        );

        CommandResult result = postingService.post(cmd);

        assertThat(result.isRejected()).isTrue();
        assertThat(result.errorCodes()).contains("JOURNAL_UNBALANCED");
    }

    @Test
    @DisplayName("TC-F002-06 post RFQ scenario two accounts atomic update")
    void post_rfqScenario_twoAccounts_atomicUpdate() {
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("1000.00"));
        setBalance("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD", new BigDecimal("5000.00"));

        PostingCommand cmd = new PostingCommand(
                "req-006", "RFQ_SETTLEMENT", "RFQ-002", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TRADE_SETTLEMENT", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.DEBIT, new BigDecimal("800.00"), "Client"),
                        new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD",
                                EntryType.CREDIT, new BigDecimal("800.00"), "Company")
                )))
        );

        CommandResult result = postingService.post(cmd);

        assertThat(result.isCompleted()).isTrue();
        Journal journal = stateMachine.getJournal(result.journalId());
        assertThat(journal.lines()).hasSize(2);

        AccountBalanceKey clientKey = new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD");
        AccountBalanceKey companyKey = new AccountBalanceKey("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD");
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
                List.of(new PostingCommand.Leg("leg-1", "TEST", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.DEBIT, new BigDecimal("100.00"), "Debit"),
                        new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD",
                                EntryType.CREDIT, new BigDecimal("100.00"), "Credit")
                )))
        );

        CommandResult result = postingService.post(cmd);

        assertThat(result.isRejected()).isTrue();
        assertThat(result.errorCodes()).contains("ACCOUNT_FROZEN");
    }
}

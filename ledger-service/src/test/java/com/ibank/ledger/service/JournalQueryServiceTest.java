package com.ibank.ledger.service;

import com.ibank.ledger.domain.command.CommandResult;
import com.ibank.ledger.domain.command.PostingCommand;
import com.ibank.ledger.domain.command.ReversalCommand;
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

@DisplayName("JournalQueryService (F-006)")
class JournalQueryServiceTest {

    private BalanceStore balanceStore;
    private AccountMetaStore accountMetaStore;
    private BalanceTypeConfigStore balanceTypeConfigStore;
    private LedgerStateMachine stateMachine;
    private JournalQueryService journalQueryService;

    @BeforeEach
    void setUp() {
        balanceStore = new BalanceStore();
        accountMetaStore = new AccountMetaStore();
        balanceTypeConfigStore = new BalanceTypeConfigStore();
        stateMachine = new LedgerStateMachine(balanceStore, accountMetaStore, balanceTypeConfigStore);
        journalQueryService = new JournalQueryService(stateMachine);

        balanceTypeConfigStore.put("AVAILABLE_BALANCE", new BalanceTypeConfig(
                "AVAILABLE_BALANCE", false, null, SignConvention.NORMAL_CREDIT, 1));

        accountMetaStore.put("CLIENT_ACC_001", new Account(
                "CLIENT_ACC_001", AccountType.CLIENT, "Client",
                "CUST-001", AccountStatus.ACTIVE, null, Instant.now()));
        accountMetaStore.put("COMPANY_FX_ACC", new Account(
                "COMPANY_FX_ACC", AccountType.COMPANY, "Company",
                null, AccountStatus.ACTIVE, null, Instant.now()));
    }

    private void setBalance(String a, String t, String c, BigDecimal amt) {
        balanceStore.put(new AccountBalanceKey(a, t, c),
                new BalanceEntry(amt, 0, 1, "", Instant.now()));
    }

    @Test
    @DisplayName("TC-F006-01 getJournal existing journalId returns journal with lines")
    void getJournal_existingJournalId_returnsJournalWithLines() {
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("1000.00"));
        setBalance("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD", new BigDecimal("5000.00"));

        PostingCommand cmd = new PostingCommand(
                "req-001", "RFQ_SETTLEMENT", "RFQ-001", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TRADE", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.DEBIT, new BigDecimal("800.00"), "Client"),
                        new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD",
                                EntryType.CREDIT, new BigDecimal("800.00"), "Company")
                )))
        );
        CommandResult result = stateMachine.applyPosting(cmd);

        Journal journal = journalQueryService.getJournal(result.journalId());

        assertThat(journal).isNotNull();
        assertThat(journal.journalId()).isEqualTo(result.journalId());
        assertThat(journal.lines()).hasSize(2);
    }

    @Test
    @DisplayName("TC-F006-02 getJournalsByAccount with filters returns paged results")
    void getJournalsByAccount_withFilters_returnsPagedResults() {
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("100000.00"));
        setBalance("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD", new BigDecimal("100000.00"));

        // Create 20 journals for CLIENT_ACC_001
        for (int i = 0; i < 20; i++) {
            PostingCommand cmd = new PostingCommand(
                    "req-j" + i, "TEST", "test-ref", LocalDate.now(),
                    List.of(new PostingCommand.Leg("leg-1", "TEST", List.of(
                            new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                    EntryType.DEBIT, new BigDecimal("1.00"), "Debit " + i),
                            new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD",
                                    EntryType.CREDIT, new BigDecimal("1.00"), "Credit " + i)
                    )))
            );
            stateMachine.applyPosting(cmd);
        }

        var page = journalQueryService.getJournalsByAccount("CLIENT_ACC_001", 0, 5);

        assertThat(page.journals()).hasSize(5);
        assertThat(page.totalCount()).isEqualTo(20);
    }

    @Test
    @DisplayName("TC-F006-03 getJournalsByBusinessEventRef returns all related journals")
    void getJournalsByBusinessEventRef_rfqId_returnsAllRelatedJournals() {
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("10000.00"));
        setBalance("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD", new BigDecimal("10000.00"));

        // Original posting
        PostingCommand cmd = new PostingCommand(
                "req-003", "RFQ_SETTLEMENT", "RFQ-001", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TRADE", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.DEBIT, new BigDecimal("100.00"), "Client"),
                        new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD",
                                EntryType.CREDIT, new BigDecimal("100.00"), "Company")
                )))
        );
        CommandResult postResult = stateMachine.applyPosting(cmd);

        // Reversal keeps the same businessEventRef
        stateMachine.applyReversal(new ReversalCommand(
                "rev-003", postResult.journalId(), "Test", "CANCEL", LocalDate.now()));

        List<Journal> journals = journalQueryService.getJournalsByBusinessEventRef("RFQ-001");

        assertThat(journals).hasSize(2);
        assertThat(journals).extracting(Journal::journalType)
                .contains(JournalType.NORMAL, JournalType.REVERSAL);
    }

    @Test
    @DisplayName("TC-F006-04 getJournalChain original journal returns full chain")
    void getJournalChain_originalJournal_returnsFullChain() {
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("10000.00"));
        setBalance("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD", new BigDecimal("10000.00"));

        // Original
        PostingCommand cmd = new PostingCommand(
                "req-004", "RFQ_SETTLEMENT", "CHAIN-001", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TRADE", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.DEBIT, new BigDecimal("100.00"), "Client"),
                        new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD",
                                EntryType.CREDIT, new BigDecimal("100.00"), "Company")
                )))
        );
        String originalId = stateMachine.applyPosting(cmd).journalId();

        // Reversal
        stateMachine.applyReversal(new ReversalCommand(
                "rev-004", originalId, "Reverse", "CANCEL", LocalDate.now()));

        // Rebook (new posting)
        PostingCommand rebookCmd = new PostingCommand(
                "req-004b", "RFQ_SETTLEMENT", "CHAIN-001", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TRADE", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.DEBIT, new BigDecimal("100.00"), "Rebook"),
                        new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD",
                                EntryType.CREDIT, new BigDecimal("100.00"), "Rebook")
                )))
        );
        stateMachine.applyPosting(rebookCmd);

        List<Journal> chain = journalQueryService.getJournalChain(originalId);

        assertThat(chain).hasSize(3);
    }

    @Test
    @DisplayName("TC-F006-05 getJournalsByRequestId confirms idempotency")
    void getJournalsByRequestId_confirmsIdempotency() {
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("1000.00"));
        setBalance("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD", new BigDecimal("5000.00"));

        PostingCommand cmd = new PostingCommand(
                "req-abc", "TEST", "test-ref", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.DEBIT, new BigDecimal("100.00"), "Test"),
                        new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD",
                                EntryType.CREDIT, new BigDecimal("100.00"), "Test")
                )))
        );
        stateMachine.applyPosting(cmd);

        Journal journal = journalQueryService.getJournalByRequestId("req-abc");

        assertThat(journal).isNotNull();
        assertThat(journal.requestId()).isEqualTo("req-abc");
    }
}

package com.tomma8.ledger.service;

import com.tomma8.ledger.domain.command.CommandResult;
import com.tomma8.ledger.domain.command.PostingCommand;
import com.tomma8.ledger.domain.exception.*;
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

@DisplayName("AdjustmentService (F-003)")
class AdjustmentServiceTest {

    private BalanceStore balanceStore;
    private AccountMetaStore accountMetaStore;
    private BalanceTypeConfigStore balanceTypeConfigStore;
    private LedgerStateMachine stateMachine;
    private AdjustmentService adjustmentService;

    @BeforeEach
    void setUp() {
        balanceStore = new BalanceStore();
        accountMetaStore = new AccountMetaStore();
        balanceTypeConfigStore = new BalanceTypeConfigStore();
        stateMachine = new LedgerStateMachine(balanceStore, accountMetaStore, balanceTypeConfigStore);
        adjustmentService = new AdjustmentService(stateMachine);

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

    private PostingCommand validAdjustment() {
        return new PostingCommand(
                "adj-req-001", "MANUAL_ADJUSTMENT", "ADJ-CASE-001", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "ADJUSTMENT", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.DEBIT, new BigDecimal("500.00"), "Adjustment debit"),
                        new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD",
                                EntryType.CREDIT, new BigDecimal("500.00"), "Adjustment credit")
                )))
        );
    }

    @Test
    @DisplayName("TC-F003-01 createDraft valid input draft created with pending status")
    void createDraft_validInput_draftCreatedWithPendingStatus() {
        AdjustmentDraft draft = adjustmentService.createDraft(validAdjustment(), "maker-001");

        assertThat(draft).isNotNull();
        assertThat(draft.status()).isEqualTo(DraftStatus.PENDING_APPROVAL);
        assertThat(draft.makerId()).isEqualTo("maker-001");
        assertThat(draft.draftId()).isNotNull();
    }

    @Test
    @DisplayName("TC-F003-02 createDraft unbalanced legs returns bad request")
    void createDraft_unbalancedLegs_returnsBadRequest() {
        PostingCommand unbalanced = new PostingCommand(
                "adj-req-002", "MANUAL_ADJUSTMENT", "ADJ-002", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "ADJUSTMENT", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.DEBIT, new BigDecimal("100.00"), "Debit"),
                        new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD",
                                EntryType.CREDIT, new BigDecimal("99.00"), "Credit")
                )))
        );

        assertThatThrownBy(() -> adjustmentService.createDraft(unbalanced, "maker-001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JOURNAL_UNBALANCED");
    }

    @Test
    @DisplayName("TC-F003-03 approveDraft valid checker adjustment posted")
    void approveDraft_validChecker_adjustmentPosted() {
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("1000.00"));
        setBalance("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD", new BigDecimal("5000.00"));

        AdjustmentDraft draft = adjustmentService.createDraft(validAdjustment(), "maker-001");
        CommandResult result = adjustmentService.approveDraft(draft.draftId(), "checker-001", "appr-001");

        assertThat(result.isCompleted()).isTrue();
        assertThat(result.journalId()).isNotNull();

        AccountBalanceKey clientKey = new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD");
        assertThat(balanceStore.getOrThrow(clientKey).amount()).isEqualByComparingTo(new BigDecimal("500.00"));
    }

    @Test
    @DisplayName("TC-F003-04 approveDraft same person as maker throws exception")
    void approveDraft_samePersonAsMaker_throwsMakerCheckerSamePersonException() {
        AdjustmentDraft draft = adjustmentService.createDraft(validAdjustment(), "ops-001");

        assertThatThrownBy(() -> adjustmentService.approveDraft(draft.draftId(), "ops-001", "appr-001"))
                .isInstanceOf(MakerCheckerSamePersonException.class);
    }

    @Test
    @DisplayName("TC-F003-05 approveDraft expired draft throws exception")
    void approveDraft_expiredDraft_throwsDraftExpiredException() {
        // Create draft with immediate expiry (already expired)
        PostingCommand cmd = validAdjustment();

        // We inject the draft manually with expiry in the past
        AdjustmentDraft expired = new AdjustmentDraft(
                "DRAFT-EXP", cmd, "maker-001", DraftStatus.PENDING_APPROVAL,
                Instant.now().minusSeconds(3600), Instant.now().minusSeconds(1800));
        adjustmentService.addDraftForTest(expired);

        assertThatThrownBy(() -> adjustmentService.approveDraft("DRAFT-EXP", "checker-001", "appr-001"))
                .isInstanceOf(DraftExpiredException.class);
    }

    @Test
    @DisplayName("TC-F003-06 approveDraft already executed draft throws exception")
    void approveDraft_alreadyExecutedDraft_throwsDraftNotPendingException() {
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("1000.00"));
        setBalance("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD", new BigDecimal("5000.00"));

        AdjustmentDraft draft = adjustmentService.createDraft(validAdjustment(), "maker-001");
        adjustmentService.approveDraft(draft.draftId(), "checker-001", "appr-001");

        assertThatThrownBy(() -> adjustmentService.approveDraft(draft.draftId(), "checker-002", "appr-002"))
                .isInstanceOf(DraftNotPendingException.class);
    }

    @Test
    @DisplayName("TC-F003-07 rejectDraft valid checker draft status rejected")
    void rejectDraft_validChecker_draftStatusRejected() {
        AdjustmentDraft draft = adjustmentService.createDraft(validAdjustment(), "maker-001");
        adjustmentService.rejectDraft(draft.draftId(), "checker-001", "Not valid adjustment");

        AdjustmentDraft retrieved = adjustmentService.getDraft(draft.draftId());
        assertThat(retrieved.status()).isEqualTo(DraftStatus.REJECTED);
    }

    @Test
    @DisplayName("TC-F003-08 approveDraft idempotent same requestId twice not double posted")
    void approveDraft_idempotent_sameRequestIdTwice_notDoublePosted() {
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("1000.00"));
        setBalance("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD", new BigDecimal("5000.00"));

        AdjustmentDraft draft = adjustmentService.createDraft(validAdjustment(), "maker-001");
        CommandResult first = adjustmentService.approveDraft(draft.draftId(), "checker-001", "appr-001");
        CommandResult second = adjustmentService.approveDraft(draft.draftId(), "checker-001", "appr-001");

        assertThat(first.isCompleted()).isTrue();
        assertThat(second).isEqualTo(first);

        AccountBalanceKey clientKey = new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD");
        assertThat(balanceStore.getOrThrow(clientKey).amount()).isEqualByComparingTo(new BigDecimal("500.00"));
    }
}

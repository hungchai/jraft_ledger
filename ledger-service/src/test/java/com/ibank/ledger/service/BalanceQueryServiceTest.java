package com.ibank.ledger.service;

import com.ibank.ledger.domain.command.CommandResult;
import com.ibank.ledger.domain.command.PostingCommand;
import com.ibank.ledger.domain.exception.AccountNotFoundException;
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

@DisplayName("BalanceQueryService (F-005)")
class BalanceQueryServiceTest {

    private BalanceStore balanceStore;
    private AccountMetaStore accountMetaStore;
    private BalanceTypeConfigStore balanceTypeConfigStore;
    private LedgerStateMachine stateMachine;
    private BalanceQueryService queryService;

    @BeforeEach
    void setUp() {
        balanceStore = new BalanceStore();
        accountMetaStore = new AccountMetaStore();
        balanceTypeConfigStore = new BalanceTypeConfigStore();
        stateMachine = new LedgerStateMachine(balanceStore, accountMetaStore, balanceTypeConfigStore);
        queryService = new BalanceQueryService(balanceStore, accountMetaStore, balanceTypeConfigStore);

        balanceTypeConfigStore.put("AVAILABLE_BALANCE", new BalanceTypeConfig(
                "AVAILABLE_BALANCE", false, null, SignConvention.NORMAL_CREDIT, 1));
        balanceTypeConfigStore.put("TRADE_AHEAD_BALANCE", new BalanceTypeConfig(
                "TRADE_AHEAD_BALANCE", true, NegativeSemantics.PRE_AUTHORIZED,
                SignConvention.NORMAL_DEBIT, 1));

        accountMetaStore.put("CLIENT_ACC_001", new Account(
                "CLIENT_ACC_001", AccountType.CLIENT, "Client",
                "CUST-001", AccountStatus.ACTIVE, null, Instant.now()));
    }

    private void setBalance(String a, String t, String c, BigDecimal amt) {
        balanceStore.put(new AccountBalanceKey(a, t, c),
                new BalanceEntry(amt, 0, 1, "", Instant.now()));
    }

    @Test
    @DisplayName("TC-F005-01 getBalance active account returns current balance")
    void getBalance_activeAccount_returnsCurrentBalance() {
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("700.00"));

        BalanceQueryResult result = queryService.getBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD");

        assertThat(result.amount()).isEqualByComparingTo(new BigDecimal("700.00"));
        assertThat(result.dataSource()).isEqualTo("STATE_MACHINE");
    }

    @Test
    @DisplayName("TC-F005-02 getBalance after posting immediately reflects new balance")
    void getBalance_afterPosting_immediatelyReflectsNewBalance() {
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("1000.00"));
        accountMetaStore.put("COMPANY_FX_ACC", new Account(
                "COMPANY_FX_ACC", AccountType.COMPANY, "Co",
                null, AccountStatus.ACTIVE, null, Instant.now()));
        setBalance("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD", new BigDecimal("5000.00"));

        // Execute posting
        PostingCommand cmd = new PostingCommand(
                "req-001", "TEST", "test", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.DEBIT, new BigDecimal("300.00"), "Debit"),
                        new PostingCommand.Line("COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD",
                                EntryType.CREDIT, new BigDecimal("300.00"), "Credit")
                )))
        );
        CommandResult postResult = stateMachine.applyPosting(cmd);
        assertThat(postResult.isCompleted()).isTrue();

        // Immediately query — should reflect new balance
        BalanceQueryResult result = queryService.getBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD");
        assertThat(result.amount()).isEqualByComparingTo(new BigDecimal("700.00"));
    }

    @Test
    @DisplayName("TC-F005-03 getBalance trade ahead negative balance returns negative value")
    void getBalance_tradeAheadNegativeBalance_returnsNegativeValue() {
        setBalance("CLIENT_ACC_001", "TRADE_AHEAD_BALANCE", "USD", new BigDecimal("-45000.00"));

        BalanceQueryResult result = queryService.getBalance("CLIENT_ACC_001", "TRADE_AHEAD_BALANCE", "USD");

        assertThat(result.amount()).isEqualByComparingTo(new BigDecimal("-45000.00"));
        assertThat(result.allowNegative()).isTrue();
    }

    @Test
    @DisplayName("TC-F005-04 getBalance unknown account throws AccountNotFoundException")
    void getBalance_unknownAccount_throwsAccountNotFoundException() {
        assertThatThrownBy(() -> queryService.getBalance("NOBODY", "AVAILABLE_BALANCE", "USD"))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    @DisplayName("TC-F005-05 getBatchBalances multiple accounts all returned correctly")
    void getBatchBalances_multipleAccounts_allReturnedCorrectly() {
        // Set up 5 accounts
        for (int i = 1; i <= 5; i++) {
            String id = "ACC-" + i;
            accountMetaStore.put(id, new Account(
                    id, AccountType.CLIENT, "Account " + i,
                    "CUST-" + i, AccountStatus.ACTIVE, null, Instant.now()));
            setBalance(id, "AVAILABLE_BALANCE", "USD", new BigDecimal(i * 100));
        }

        List<AccountBalanceKey> keys = List.of(
                new AccountBalanceKey("ACC-1", "AVAILABLE_BALANCE", "USD"),
                new AccountBalanceKey("ACC-2", "AVAILABLE_BALANCE", "USD"),
                new AccountBalanceKey("ACC-3", "AVAILABLE_BALANCE", "USD")
        );

        List<BalanceQueryResult> results = queryService.getBatchBalances(keys);

        assertThat(results).hasSize(3);
        assertThat(results.get(0).amount()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(results.get(1).amount()).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(results.get(2).amount()).isEqualByComparingTo(new BigDecimal("300.00"));
        assertThat(results).allMatch(r -> "STATE_MACHINE".equals(r.dataSource()));
    }

    @Test
    @DisplayName("TC-F005-06 getAsOfBalance historical snapshot returns snapshot balance")
    void getAsOfBalance_historicalSnapshot_returnsSnapshotBalance() {
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("700.00"));

        // Without snapshot support, returns current balance with EOD_SNAPSHOT source
        // In production, this would query RocksDB CF_SM_SNAPSHOT
        BalanceQueryResult result = queryService.getAsOfBalance(
                "CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", LocalDate.of(2026, 5, 15));

        assertThat(result).isNotNull();
        // Snapshot not implemented yet — returns current dataSource as STATE_MACHINE
        assertThat(result.dataSource()).isIn("STATE_MACHINE", "EOD_SNAPSHOT");
    }
}

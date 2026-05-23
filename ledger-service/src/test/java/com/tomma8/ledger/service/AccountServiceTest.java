package com.tomma8.ledger.service;

import com.tomma8.ledger.domain.command.AccountCreateCommand;
import com.tomma8.ledger.domain.command.AccountFreezeCommand;
import com.tomma8.ledger.domain.command.CommandResult;
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
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("AccountService (F-010)")
class AccountServiceTest {

    private BalanceTypeConfigStore balanceTypeConfigStore;
    private AccountMetaStore accountMetaStore;
    private BalanceStore balanceStore;
    private LedgerStateMachine stateMachine;
    private AccountService accountService;

    @BeforeEach
    void setUp() {
        balanceTypeConfigStore = new BalanceTypeConfigStore();
        accountMetaStore = new AccountMetaStore();
        balanceStore = new BalanceStore();
        stateMachine = new LedgerStateMachine(balanceStore, accountMetaStore, balanceTypeConfigStore);
        accountService = new AccountService(stateMachine, balanceTypeConfigStore);

        // Register required balance types
        balanceTypeConfigStore.put("AVAILABLE_BALANCE", new BalanceTypeConfig(
                "AVAILABLE_BALANCE", false, null,
                SignConvention.NORMAL_CREDIT, 1));
        balanceTypeConfigStore.put("TRADE_AHEAD_BALANCE", new BalanceTypeConfig(
                "TRADE_AHEAD_BALANCE", true, NegativeSemantics.PRE_AUTHORIZED,
                SignConvention.NORMAL_DEBIT, 1));
        balanceTypeConfigStore.put("BROKERAGE_BALANCE", new BalanceTypeConfig(
                "BROKERAGE_BALANCE", false, null,
                SignConvention.NORMAL_CREDIT, 1));
    }

    @Test
    @DisplayName("TC-F010-01 createAccount valid input account created in StateMachine")
    void createAccount_validInput_accountCreatedInStateMachine() {
        CommandResult result = accountService.createAccount(new AccountCreateCommand(
                "req-001", "CLIENT_ACC_001", AccountType.CLIENT,
                "Client Account 001", "CUST-001",
                List.of(new AccountCreateCommand.BalanceInitialization("AVAILABLE_BALANCE", "USD"))
        ));

        assertThat(result.isCompleted()).isTrue();
        assertThat(accountMetaStore.get("CLIENT_ACC_001")).isPresent();
        assertThat(accountMetaStore.getOrThrow("CLIENT_ACC_001").status()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("TC-F010-02 createAccount duplicate accountId returns rejected")
    void createAccount_duplicateAccountId_returnsRejected() {
        accountService.createAccount(new AccountCreateCommand(
                "req-001", "CLIENT_ACC_001", AccountType.CLIENT,
                "Client", "CUST-001",
                List.of(new AccountCreateCommand.BalanceInitialization("AVAILABLE_BALANCE", "USD"))
        ));

        CommandResult result = accountService.createAccount(new AccountCreateCommand(
                "req-002", "CLIENT_ACC_001", AccountType.CLIENT,
                "Client Dup", "CUST-002",
                List.of(new AccountCreateCommand.BalanceInitialization("AVAILABLE_BALANCE", "USD"))
        ));
        assertThat(result.isRejected()).isTrue();
        assertThat(result.errorCodes()).contains("ACCOUNT_ALREADY_EXISTS");
    }

    @Test
    @DisplayName("TC-F010-03 createAccount client type without ownerId throws MissingOwnerIdException")
    void createAccount_clientTypeWithoutOwnerId_throwsMissingOwnerIdException() {
        assertThatThrownBy(() -> accountService.createAccount(new AccountCreateCommand(
                "req-001", "CLIENT_ACC_002", AccountType.CLIENT,
                "No Owner", null,
                List.of(new AccountCreateCommand.BalanceInitialization("AVAILABLE_BALANCE", "USD"))
        ))).isInstanceOf(MissingOwnerIdException.class);
    }

    @Test
    @DisplayName("TC-F010-04 createAccount with balance initializations balances initialized to zero")
    void createAccount_withBalanceInitializations_balancesInitializedToZero() {
        accountService.createAccount(new AccountCreateCommand(
                "req-001", "CLIENT_ACC_001", AccountType.CLIENT,
                "Client", "CUST-001",
                List.of(
                        new AccountCreateCommand.BalanceInitialization("AVAILABLE_BALANCE", "USD"),
                        new AccountCreateCommand.BalanceInitialization("AVAILABLE_BALANCE", "HKD")
                )
        ));

        AccountBalanceKey usdKey = new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", "USD");
        AccountBalanceKey hkdKey = new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", "HKD");

        assertThat(balanceStore.get(usdKey)).isPresent();
        assertThat(balanceStore.getOrThrow(usdKey).amount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(balanceStore.get(hkdKey)).isPresent();
        assertThat(balanceStore.getOrThrow(hkdKey).amount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("TC-F010-05 createAccount with unknown balance type throws BalanceTypeNotFoundException")
    void createAccount_withUnknownBalanceType_throwsBalanceTypeNotFoundException() {
        assertThatThrownBy(() -> accountService.createAccount(new AccountCreateCommand(
                "req-001", "CLIENT_ACC_001", AccountType.CLIENT,
                "Client", "CUST-001",
                List.of(new AccountCreateCommand.BalanceInitialization("UNKNOWN_TYPE", "USD"))
        ))).isInstanceOf(BalanceTypeNotFoundException.class);
    }

    @Test
    @DisplayName("TC-F010-06 freezeAccount active account status becomes frozen")
    void freezeAccount_activeAccount_statusBecomeFrozen() {
        accountService.createAccount(new AccountCreateCommand(
                "req-001", "CLIENT_ACC_001", AccountType.CLIENT,
                "Client", "CUST-001",
                List.of(new AccountCreateCommand.BalanceInitialization("AVAILABLE_BALANCE", "USD"))
        ));

        accountService.freezeAccount(new AccountFreezeCommand("req-002", "CLIENT_ACC_001", true));

        assertThat(accountMetaStore.getOrThrow("CLIENT_ACC_001").status()).isEqualTo(AccountStatus.FROZEN);
    }

    @Test
    @DisplayName("TC-F010-07 unfreezeAccount frozen account status becomes active")
    void unfreezeAccount_frozenAccount_statusBecomeActive() {
        accountService.createAccount(new AccountCreateCommand(
                "req-001", "CLIENT_ACC_001", AccountType.CLIENT,
                "Client", "CUST-001",
                List.of(new AccountCreateCommand.BalanceInitialization("AVAILABLE_BALANCE", "USD"))
        ));
        accountService.freezeAccount(new AccountFreezeCommand("req-002", "CLIENT_ACC_001", true));

        accountService.unfreezeAccount(new AccountFreezeCommand("req-003", "CLIENT_ACC_001", false));

        assertThat(accountMetaStore.getOrThrow("CLIENT_ACC_001").status()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("TC-F010-08 closeAccount with non-zero balance throws AccountHasNonZeroBalanceException")
    void closeAccount_withNonZeroBalance_throwsAccountHasNonZeroBalanceException() {
        accountService.createAccount(new AccountCreateCommand(
                "req-001", "CLIENT_ACC_001", AccountType.CLIENT,
                "Client", "CUST-001",
                List.of(new AccountCreateCommand.BalanceInitialization("AVAILABLE_BALANCE", "USD"))
        ));

        // Set a non-zero balance
        AccountBalanceKey key = new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", "USD");
        balanceStore.put(key, new BalanceEntry(new BigDecimal("100.00"), 1, 1, "JNL-001", Instant.now()));

        assertThatThrownBy(() -> accountService.closeAccount("CLIENT_ACC_001", "req-004"))
                .isInstanceOf(AccountHasNonZeroBalanceException.class);
    }

    @Test
    @DisplayName("TC-F010-09 closeAccount with all zero balances status becomes closed")
    void closeAccount_withAllZeroBalances_statusBecomeClosed() {
        accountService.createAccount(new AccountCreateCommand(
                "req-001", "CLIENT_ACC_001", AccountType.CLIENT,
                "Client", "CUST-001",
                List.of(new AccountCreateCommand.BalanceInitialization("AVAILABLE_BALANCE", "USD"))
        ));

        CommandResult result = accountService.closeAccount("CLIENT_ACC_001", "req-002");

        assertThat(result.isCompleted()).isTrue();
        assertThat(accountMetaStore.getOrThrow("CLIENT_ACC_001").status()).isEqualTo(AccountStatus.CLOSED);
    }

    @Test
    @DisplayName("TC-F010-10 closeAccount closed account cannot be unfrozen")
    void closeAccount_closedAccount_cannotBeUnfrozen() {
        accountService.createAccount(new AccountCreateCommand(
                "req-001", "CLIENT_ACC_001", AccountType.CLIENT,
                "Client", "CUST-001",
                List.of(new AccountCreateCommand.BalanceInitialization("AVAILABLE_BALANCE", "USD"))
        ));
        accountService.closeAccount("CLIENT_ACC_001", "req-002");

        assertThatThrownBy(() -> accountService.unfreezeAccount(
                new AccountFreezeCommand("req-003", "CLIENT_ACC_001", false)))
                .isInstanceOf(AccountClosedException.class);
    }

    @Test
    @DisplayName("TC-F010-11 addBalanceType existing account new balance initialized to zero")
    void addBalanceType_existingAccount_newBalanceInitializedToZero() {
        accountService.createAccount(new AccountCreateCommand(
                "req-001", "CLIENT_ACC_001", AccountType.CLIENT,
                "Client", "CUST-001",
                List.of(new AccountCreateCommand.BalanceInitialization("AVAILABLE_BALANCE", "USD"))
        ));

        accountService.addBalanceType("CLIENT_ACC_001", "BROKERAGE_BALANCE", "USD", "req-002");

        AccountBalanceKey newKey = new AccountBalanceKey("CLIENT_ACC_001", "BROKERAGE_BALANCE", "CURRENT", "USD");
        assertThat(balanceStore.get(newKey)).isPresent();
        assertThat(balanceStore.getOrThrow(newKey).amount()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}

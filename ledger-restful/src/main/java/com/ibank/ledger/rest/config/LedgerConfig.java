package com.ibank.ledger.rest.config;

import com.ibank.ledger.domain.model.BalanceTypeConfig;
import com.ibank.ledger.domain.model.NegativeSemantics;
import com.ibank.ledger.domain.model.SignConvention;
import com.ibank.ledger.service.*;
import com.ibank.ledger.statemachine.LedgerStateMachine;
import com.ibank.ledger.store.AccountMetaStore;
import com.ibank.ledger.store.BalanceStore;
import com.ibank.ledger.store.BalanceTypeConfigStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LedgerConfig {

    @Bean
    public BalanceStore balanceStore() {
        return new BalanceStore();
    }

    @Bean
    public AccountMetaStore accountMetaStore() {
        return new AccountMetaStore();
    }

    @Bean
    public BalanceTypeConfigStore balanceTypeConfigStore() {
        return new BalanceTypeConfigStore();
    }

    @Bean
    public LedgerStateMachine ledgerStateMachine(
            BalanceStore balanceStore,
            AccountMetaStore accountMetaStore,
            BalanceTypeConfigStore balanceTypeConfigStore) {
        return new LedgerStateMachine(balanceStore, accountMetaStore, balanceTypeConfigStore);
    }

    @Bean
    public BalanceTypeConfigService balanceTypeConfigService() {
        return new BalanceTypeConfigService();
    }

    @Bean
    public AccountService accountService(
            LedgerStateMachine stateMachine,
            BalanceTypeConfigStore balanceTypeConfigStore) {
        return new AccountService(stateMachine, balanceTypeConfigStore);
    }

    @Bean
    public PostingService postingService(LedgerStateMachine stateMachine) {
        return new PostingService(stateMachine);
    }

    @Bean
    public ReversalService reversalService(LedgerStateMachine stateMachine) {
        return new ReversalService(stateMachine);
    }

    @Bean
    public AdjustmentService adjustmentService(LedgerStateMachine stateMachine) {
        return new AdjustmentService(stateMachine);
    }

    @Bean
    public BalanceQueryService balanceQueryService(
            BalanceStore balanceStore,
            AccountMetaStore accountMetaStore,
            BalanceTypeConfigStore balanceTypeConfigStore) {
        return new BalanceQueryService(balanceStore, accountMetaStore, balanceTypeConfigStore);
    }

    @Bean
    public JournalQueryService journalQueryService(LedgerStateMachine stateMachine) {
        return new JournalQueryService(stateMachine);
    }

    @Bean
    public ReconciliationService reconciliationService(LedgerStateMachine stateMachine) {
        return new ReconciliationService(stateMachine);
    }

    @Bean
    public AccountingPeriodService accountingPeriodService() {
        return new AccountingPeriodService();
    }

    @Bean
    CommandLineRunner initDefaultTypes(BalanceTypeConfigStore configStore,
                                        BalanceTypeConfigService configService) {
        return args -> {
            configStore.put("AVAILABLE_BALANCE", new BalanceTypeConfig(
                    "AVAILABLE_BALANCE", false, null, SignConvention.NORMAL_CREDIT, 1));
            configStore.put("TRADE_AHEAD_BALANCE", new BalanceTypeConfig(
                    "TRADE_AHEAD_BALANCE", true, NegativeSemantics.PRE_AUTHORIZED,
                    SignConvention.NORMAL_DEBIT, 1));
            configStore.put("BROKERAGE_BALANCE", new BalanceTypeConfig(
                    "BROKERAGE_BALANCE", false, null, SignConvention.NORMAL_CREDIT, 1));

            configService.registerType(new BalanceTypeConfig(
                    "AVAILABLE_BALANCE", false, null, SignConvention.NORMAL_CREDIT, 1));
            configService.registerType(new BalanceTypeConfig(
                    "TRADE_AHEAD_BALANCE", true, NegativeSemantics.PRE_AUTHORIZED,
                    SignConvention.NORMAL_DEBIT, 1));
            configService.registerType(new BalanceTypeConfig(
                    "BROKERAGE_BALANCE", false, null, SignConvention.NORMAL_CREDIT, 1));
        };
    }
}

package com.tomma8.ledger.raft.ratis;

import com.tomma8.ledger.domain.command.CommandResult;
import com.tomma8.ledger.domain.command.PostingCommand;
import com.tomma8.ledger.domain.model.Account;
import com.tomma8.ledger.domain.model.AccountBalanceKey;
import com.tomma8.ledger.domain.model.AccountStatus;
import com.tomma8.ledger.domain.model.AccountType;
import com.tomma8.ledger.domain.model.BalanceEntry;
import com.tomma8.ledger.domain.model.BalanceTypeConfig;
import com.tomma8.ledger.domain.model.EntryType;
import com.tomma8.ledger.domain.model.SignConvention;
import com.tomma8.ledger.raft.NodeRole;
import com.tomma8.ledger.statemachine.LedgerStateMachine;
import com.tomma8.ledger.store.AccountMetaStore;
import com.tomma8.ledger.store.BalanceStore;
import com.tomma8.ledger.store.BalanceTypeConfigStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * In-JVM single-node Apache Ratis cluster — validates the full
 * submit → Raft log → applyTransaction → LedgerStateMachine → reply → snapshot path
 * without docker (TC-RAFT-RATIS-01/02/03).
 */
@DisplayName("Ratis Consensus Engine — single-node integration")
class RatisEngineIntegrationTest {

    private BalanceStore balanceStore;
    private AccountMetaStore accountMetaStore;
    private BalanceTypeConfigStore balanceTypeConfigStore;
    private LedgerStateMachine ledger;
    private RatisNodeManager engine;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        balanceStore = new BalanceStore();
        accountMetaStore = new AccountMetaStore();
        balanceTypeConfigStore = new BalanceTypeConfigStore();
        ledger = new LedgerStateMachine(balanceStore, accountMetaStore, balanceTypeConfigStore);

        balanceTypeConfigStore.put("AVAILABLE_BALANCE", new BalanceTypeConfig(
                "AVAILABLE_BALANCE", false, null, SignConvention.NORMAL_CREDIT, 1));
        accountMetaStore.put("CLIENT_ACC_001", new Account(
                "CLIENT_ACC_001", AccountType.COMPANY, "Client 001",
                "CUST-001", AccountStatus.ACTIVE, null, Instant.now()));
        setBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", new BigDecimal("1000.00"));

        int port = freePort();
        String group = "ratis-it-" + UUID.randomUUID();
        String peers = "n1:127.0.0.1:" + port;
        RatisLedgerStateMachine fsm = new RatisLedgerStateMachine(ledger, new NodeRole());
        engine = new RatisNodeManager(group, "n1", peers, tmp.toString(), port, fsm);
        engine.init();

        await().atMost(20, SECONDS).until(engine::isLeader);
    }

    @AfterEach
    void tearDown() {
        if (engine != null) engine.close();
    }

    @Test
    @DisplayName("TC-RAFT-RATIS-01 posting committed through Ratis log mutates balance")
    void posting_committedThroughRatis() {
        PostingCommand cmd = new PostingCommand(
                "req-ratis-1", "TEST", "ratis-ref", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST_TYPE", new BigDecimal("300.00"), "USD", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", EntryType.DEBIT, "ratis debit")
                ))));

        CommandResult result = engine.submit(cmd);

        assertThat(result.isCompleted()).isTrue();
        AccountBalanceKey key = new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", "USD");
        assertThat(balanceStore.getOrThrow(key).amount()).isEqualByComparingTo(new BigDecimal("700.00"));
        assertThat(engine.getLastAppliedIndex()).isGreaterThan(0L);
    }

    @Test
    @DisplayName("TC-RAFT-RATIS-02 duplicate requestId is idempotent (cached result)")
    void posting_idempotentOnReplay() {
        PostingCommand cmd = new PostingCommand(
                "req-ratis-dup", "TEST", "ratis-ref", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST_TYPE", new BigDecimal("100.00"), "USD", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", EntryType.DEBIT, "dup debit")
                ))));

        CommandResult first = engine.submit(cmd);
        CommandResult second = engine.submit(cmd);

        assertThat(first.isCompleted()).isTrue();
        assertThat(second.isCompleted()).isTrue();
        // Balance debited once, not twice.
        AccountBalanceKey key = new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", "USD");
        assertThat(balanceStore.getOrThrow(key).amount()).isEqualByComparingTo(new BigDecimal("900.00"));
    }

    private void setBalance(String accountId, String balanceType, String currency, BigDecimal amount) {
        AccountBalanceKey key = new AccountBalanceKey(accountId, balanceType, "CURRENT", currency);
        balanceStore.put(key, new BalanceEntry(amount, 0, 1, "", Instant.now()));
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}

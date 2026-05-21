package com.ibank.ledger.rest.config;

import com.ibank.ledger.domain.model.BalanceTypeConfig;
import com.ibank.ledger.domain.model.NegativeSemantics;
import com.ibank.ledger.domain.model.SignConvention;
import com.ibank.ledger.event.KafkaEventPublisher;
import com.ibank.ledger.raft.LedgerRaftStateMachine;
import com.ibank.ledger.raft.NodeRole;
import com.ibank.ledger.raft.RaftNodeManager;
import com.ibank.ledger.rocksdb.OutboxStore;
import com.ibank.ledger.rocksdb.RocksDBManager;
import com.ibank.ledger.service.*;
import com.ibank.ledger.statemachine.LedgerStateMachine;
import com.ibank.ledger.store.AccountMetaStore;
import com.ibank.ledger.store.BalanceStore;
import com.ibank.ledger.store.BalanceTypeConfigStore;
import java.io.File;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
public class LedgerConfig {

    private static final Logger log = LoggerFactory.getLogger(LedgerConfig.class);

    @Bean
    public NodeRole nodeRole(org.springframework.core.env.Environment env) {
        NodeRole nr = new NodeRole();
        String envNodeId = System.getenv("NODE_ID");
        String nodeName = envNodeId != null ? envNodeId : "standalone";
        if (env.acceptsProfiles(org.springframework.core.env.Profiles.of("test"))
                || System.getenv("PEER_NODES") == null) {
            nr.setLeader(nodeName, 0);
        } else {
            nr.setFollower(nodeName);
        }
        return nr;
    }

    @Bean
    public BalanceStore balanceStore() { return new BalanceStore(); }

    @Bean
    public AccountMetaStore accountMetaStore() { return new AccountMetaStore(); }

    @Bean
    public BalanceTypeConfigStore balanceTypeConfigStore() { return new BalanceTypeConfigStore(); }

    @Bean(destroyMethod = "close")
    @Profile("!test")
    public RocksDBManager rocksDBManager() {
        String dbPath = System.getenv().getOrDefault("LEDGER_ROCKSDB_PATH", "/tmp/ledger/rocksdb");
        RocksDBManager mgr = new RocksDBManager(dbPath);
        try {
            mgr.open();
            log.info("RocksDB opened at {}", dbPath);
        } catch (Exception e) {
            log.error("Failed to open RocksDB at {} — running in-memory only", dbPath, e);
        }
        return mgr;
    }

    @Bean
    @Profile("!test")
    public OutboxStore outboxStore(RocksDBManager rocksDBManager) {
        return new OutboxStore(rocksDBManager);
    }

    @Bean(destroyMethod = "close")
    public KafkaEventPublisher kafkaEventPublisher() {
        String brokers = System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
        return new KafkaEventPublisher(brokers, "ledger.balance.change.v1");
    }

    @Bean
    public LedgerStateMachine ledgerStateMachine(
            BalanceStore balanceStore,
            AccountMetaStore accountMetaStore,
            BalanceTypeConfigStore balanceTypeConfigStore,
            @org.springframework.beans.factory.annotation.Autowired(required = false) RocksDBManager rocksDBManager,
            @org.springframework.beans.factory.annotation.Autowired(required = false) OutboxStore outboxStore,
            @org.springframework.beans.factory.annotation.Autowired(required = false) KafkaEventPublisher kafkaPublisher) {
        LedgerStateMachine sm = new LedgerStateMachine(balanceStore, accountMetaStore, balanceTypeConfigStore);

        // Wire Kafka event publisher
        if (kafkaPublisher != null) {
            sm.setEventListener(kafkaPublisher);
            log.info("Kafka event publisher wired");
        }

        if (rocksDBManager != null && rocksDBManager.isOpen()) {
            sm.setRocksDB(rocksDBManager);
            sm.setOutboxStore(outboxStore);
            sm.setPersistAfterApply(true);
            try {
                sm.restoreFromSnapshot();
                log.info("StateMachine restored from RocksDB snapshot");
            } catch (Exception e) {
                log.info("No snapshot found — starting fresh");
            }
        } else {
            log.info("RocksDB not available — running in-memory only");
        }
        return sm;
    }

    @Bean
    public BalanceTypeConfigService balanceTypeConfigService() { return new BalanceTypeConfigService(); }

    @Bean
    public AccountService accountService(LedgerStateMachine sm, BalanceTypeConfigStore cs) {
        return new AccountService(sm, cs);
    }
    @Bean
    public PostingService postingService(LedgerStateMachine sm) { return new PostingService(sm); }
    @Bean
    public ReversalService reversalService(LedgerStateMachine sm) { return new ReversalService(sm); }
    @Bean
    public AdjustmentService adjustmentService(LedgerStateMachine sm) { return new AdjustmentService(sm); }
    @Bean
    public BalanceQueryService balanceQueryService(BalanceStore bs, AccountMetaStore ams, BalanceTypeConfigStore cs) {
        return new BalanceQueryService(bs, ams, cs);
    }
    @Bean
    public JournalQueryService journalQueryService(LedgerStateMachine sm) { return new JournalQueryService(sm); }
    @Bean
    public ReconciliationService reconciliationService(LedgerStateMachine sm) { return new ReconciliationService(sm); }
    @Bean
    public AccountingPeriodService accountingPeriodService() { return new AccountingPeriodService(); }

    @Bean(destroyMethod = "close")
    public RaftNodeManager raftNodeManager(
            LedgerStateMachine ledgerStateMachine,
            NodeRole nodeRole,
            @org.springframework.beans.factory.annotation.Value("${ledger.raft.group-id:ledger-group-1}") String groupId) {
        String nodeId   = System.getenv("NODE_ID");
        String peers    = System.getenv("PEER_NODES");
        String raftPath = System.getenv().getOrDefault("LEDGER_RAFT_DATA_PATH", "/tmp/ledger/raft");
        String raftPort = System.getenv().getOrDefault("RAFT_SERVER_PORT", "28080");

        if (nodeId == null || peers == null) {
            log.info("Raft cluster not configured — running standalone");
            return null;
        }

        LedgerRaftStateMachine fsm = new LedgerRaftStateMachine(ledgerStateMachine)
                .withNodeRole(nodeRole);
        fsm.setNodeId(nodeId);

        String[] peerArr = peers.split(",");
        StringBuilder raftPeers = new StringBuilder();
        for (String p : peerArr) {
            String host = p.split(":")[0].trim();
            if (raftPeers.length() > 0) raftPeers.append(",");
            raftPeers.append(host).append(":").append(raftPort);
        }

        String serverId = nodeId + ":" + raftPort;
        new File(raftPath).mkdirs(); // ensure dir exists before SOFAJRaft init
        RaftNodeManager mgr = new RaftNodeManager(
                groupId, serverId, raftPeers.toString(),
                raftPath, fsm);
        mgr.init();
        log.info("Raft node started: {} peers={}", serverId, raftPeers);
        return mgr;
    }

    @Bean
    CommandLineRunner initDefaultTypes(BalanceTypeConfigStore configStore,
                                        BalanceTypeConfigService configService,
                                        BalanceStore balanceStore,
                                        AccountMetaStore accountMetaStore,
                                        LedgerStateMachine ledgerStateMachine) {
        return args -> {
            // Register balance types
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

            // Bootstrap institutional accounts — routed through state machine so events are emitted
            String[] bootstrapAccounts = {"COMPANY_FX_ACC", "NOSTRO_USD", "SUSPENSE_USD"};
            for (String id : bootstrapAccounts) {
                if (!accountMetaStore.contains(id)) {
                    ledgerStateMachine.applyAccountCreate(new com.ibank.ledger.domain.command.AccountCreateCommand(
                            "bootstrap-" + id, id,
                            com.ibank.ledger.domain.model.AccountType.COMPANY,
                            "Bootstrap " + id, null,
                            java.util.List.of(new com.ibank.ledger.domain.command.AccountCreateCommand.BalanceInitialization(
                                    "AVAILABLE_BALANCE", "USD"))));
                    log.info("Bootstrap account created: {}", id);
                }
            }
        };
    }
}

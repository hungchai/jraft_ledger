package com.tomma8.ledger.rest.config;

import com.tomma8.ledger.domain.model.BalanceTypeConfig;
import com.tomma8.ledger.domain.model.NegativeSemantics;
import com.tomma8.ledger.domain.model.SignConvention;
import com.tomma8.ledger.event.AsyncOutboxPublisher;
import com.tomma8.ledger.event.KafkaEventPublisher;
import com.tomma8.ledger.raft.LedgerRaftStateMachine;
import com.tomma8.ledger.raft.NodeRole;
import com.tomma8.ledger.raft.RaftNodeManager;
import com.tomma8.ledger.rocksdb.OutboxStore;
import com.tomma8.ledger.rocksdb.RocksDBManager;
import com.tomma8.ledger.service.*;
import com.tomma8.ledger.rest.controller.ClusterController;
import com.tomma8.ledger.statemachine.LedgerStateMachine;
import com.tomma8.ledger.queue.AccountQueueManager;
import com.tomma8.ledger.store.AccountMetaStore;
import com.tomma8.ledger.store.BalanceStore;
import com.tomma8.ledger.store.BalanceTypeConfigStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import java.io.File;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
public class LedgerConfig {

    private static final Logger log = LoggerFactory.getLogger(LedgerConfig.class);

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> ledgerMetricsCustomizer() {
        return registry -> registry.config().meterFilter(new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(io.micrometer.core.instrument.Meter.Id id, DistributionStatisticConfig config) {
                if (id.getName().startsWith("ledger.")) {
                    return DistributionStatisticConfig.builder()
                            .percentilesHistogram(true)
                            .minimumExpectedValue(1.0)
                            .maximumExpectedValue(10000.0)
                            .build()
                            .merge(config);
                }
                return config;
            }
        });
    }

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
    @Profile("!test")
    public KafkaEventPublisher kafkaEventPublisher() {
        String brokers = System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
        return new KafkaEventPublisher(brokers, "ledger.balance.change.v1");
    }

    @Bean(destroyMethod = "close")
    @Profile("!test")
    public AsyncOutboxPublisher asyncOutboxPublisher(OutboxStore outboxStore,
                                                      KafkaEventPublisher kafkaPublisher,
                                                      MeterRegistry meterRegistry) {
        Duration pollInterval = Duration.ofSeconds(
                Long.parseLong(System.getenv().getOrDefault("OUTBOX_POLL_INTERVAL_SECS", "10")));
        int batchSize = Integer.parseInt(
                System.getenv().getOrDefault("OUTBOX_BATCH_SIZE", "100"));
        AsyncOutboxPublisher publisher = new AsyncOutboxPublisher(
                outboxStore, kafkaPublisher, pollInterval, batchSize);

        // Register Micrometer gauges
        Gauge.builder("ledger.outbox.pending", outboxStore::pendingCount)
                .description("Outbox events pending in CF_OUTBOX")
                .register(meterRegistry);
        Gauge.builder("ledger.outbox.published", publisher::getPublishedCount)
                .description("Total outbox events published to Kafka")
                .register(meterRegistry);
        Gauge.builder("ledger.outbox.failed", publisher::getFailedCount)
                .description("Total outbox events that failed to publish")
                .register(meterRegistry);
        Gauge.builder("ledger.outbox.last_scan_pending", publisher::getLastScanPending)
                .description("Pending events found in last outbox scan")
                .register(meterRegistry);
        Gauge.builder("ledger.outbox.last_scan_duration_ms", publisher::getLastScanDurationMs)
                .description("Duration of last outbox scan in milliseconds")
                .register(meterRegistry);

        log.info("AsyncOutboxPublisher wired with pollInterval={} batchSize={}", pollInterval, batchSize);
        return publisher;
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
            // Snapshot restore happens via onSnapshotLoad() from leader transfer
            // during Raft startup. Local RocksDB restore removed — stale local
            // snapshots caused non-deterministic replay and balance divergence.
            log.info("StateMachine wired — snapshot will load via Raft leader transfer");
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

    @Bean
    public ClusterController clusterController(NodeRole nodeRole,
            MeterRegistry meterRegistry,
            @org.springframework.beans.factory.annotation.Autowired(required = false) RaftNodeManager raftNodeManager) {
        return new ClusterController(nodeRole, raftNodeManager);
    }

    @Bean(destroyMethod = "close")
    public RaftNodeManager raftNodeManager(
            LedgerStateMachine ledgerStateMachine,
            NodeRole nodeRole,
            MeterRegistry meterRegistry,
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

        // Register Raft gauges for Prometheus
        Gauge.builder("ledger.raft.is_leader", () -> fsm.isLeader() ? 1.0 : 0.0)
                .description("Raft leader status: 1 = leader, 0 = follower")
                .tag("node_id", nodeId)
                .register(meterRegistry);
        Gauge.builder("ledger.raft.last_applied_index", fsm::getLastAppliedIndex)
                .description("Raft last applied index (monotonic, per node)")
                .tag("node_id", nodeId)
                .register(meterRegistry);

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

    @Bean(destroyMethod = "close")
    public AccountQueueManager accountQueueManager(RaftNodeManager raftNodeManager,
                                                   MeterRegistry meterRegistry) {
        if (raftNodeManager == null) {
            log.info("AccountQueueManager not created — standalone mode");
            return null;
        }
        AccountQueueManager aqm = new AccountQueueManager(raftNodeManager::submit);

        // Register queue depth gauges for hot accounts
        Set<String> hotAccounts = Set.of(
                "STRESS-HOT-CO-001", "COMPANY_FX_ACC", "NOSTRO_USD", "SUSPENSE_USD");
        AtomicInteger gaugeSeq = new AtomicInteger(0);
        for (String acc : hotAccounts) {
            int idx = gaugeSeq.getAndIncrement();
            Gauge.builder("ledger.account.queue.depth", () -> aqm.getQueueDepth(acc))
                    .description("Depth of pending command queue for account")
                    .tag("accountId", acc)
                    .register(meterRegistry);
        }
        Gauge.builder("ledger.account.queue.active", () -> aqm.getActiveAccountCount())
                .description("Number of active account queues")
                .register(meterRegistry);

        log.info("AccountQueueManager wired — {} hot-account gauges registered", hotAccounts.size());
        return aqm;
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
            // SYSTEM_SEED needs USD + BTC (used as counterparty for k6/stress seed postings)
            // BANK_SETTLEMENT is the counterparty for deposits/withdrawals (allow negative)
            record BootstrapAccount(String id, com.tomma8.ledger.domain.model.AccountType type,
                                    java.util.List<com.tomma8.ledger.domain.command.AccountCreateCommand.BalanceInitialization> balances) {}
            java.util.List<BootstrapAccount> bootstrapAccounts = java.util.List.of(
                    new BootstrapAccount("COMPANY_FX_ACC", com.tomma8.ledger.domain.model.AccountType.COMPANY,
                            java.util.List.of(new com.tomma8.ledger.domain.command.AccountCreateCommand.BalanceInitialization("AVAILABLE_BALANCE", "USD"))),
                    new BootstrapAccount("NOSTRO_USD", com.tomma8.ledger.domain.model.AccountType.COMPANY,
                            java.util.List.of(new com.tomma8.ledger.domain.command.AccountCreateCommand.BalanceInitialization("AVAILABLE_BALANCE", "USD"))),
                    new BootstrapAccount("SUSPENSE_USD", com.tomma8.ledger.domain.model.AccountType.SUSPENSE,
                            java.util.List.of(new com.tomma8.ledger.domain.command.AccountCreateCommand.BalanceInitialization("AVAILABLE_BALANCE", "USD"))),
                    new BootstrapAccount("SYSTEM_SEED", com.tomma8.ledger.domain.model.AccountType.COMPANY,
                            java.util.List.of(
                                    new com.tomma8.ledger.domain.command.AccountCreateCommand.BalanceInitialization("AVAILABLE_BALANCE", "USD"),
                                    new com.tomma8.ledger.domain.command.AccountCreateCommand.BalanceInitialization("AVAILABLE_BALANCE", "BTC"),
                                    new com.tomma8.ledger.domain.command.AccountCreateCommand.BalanceInitialization("AVAILABLE_BALANCE", "USDT"))),
                    new BootstrapAccount("BANK_SETTLEMENT", com.tomma8.ledger.domain.model.AccountType.BANK,
                            java.util.List.of(
                                    new com.tomma8.ledger.domain.command.AccountCreateCommand.BalanceInitialization("AVAILABLE_BALANCE", "USD"),
                                    new com.tomma8.ledger.domain.command.AccountCreateCommand.BalanceInitialization("AVAILABLE_BALANCE", "BTC"),
                                    new com.tomma8.ledger.domain.command.AccountCreateCommand.BalanceInitialization("AVAILABLE_BALANCE", "USDT"))));
            for (var ba : bootstrapAccounts) {
                if (!accountMetaStore.contains(ba.id())) {
                    ledgerStateMachine.applyAccountCreate(new com.tomma8.ledger.domain.command.AccountCreateCommand(
                            "bootstrap-" + ba.id(), ba.id(), ba.type(),
                            "Bootstrap " + ba.id(), null, ba.balances()));
                    log.info("Bootstrap account created: {} (type={})", ba.id(), ba.type());
                }
            }
        };
    }
}

package com.tomma8.ledger.rest.config;

import com.tomma8.ledger.domain.model.BalanceTypeConfig;
import com.tomma8.ledger.domain.model.NegativeSemantics;
import com.tomma8.ledger.domain.model.SignConvention;
import com.tomma8.ledger.event.AsyncOutboxPublisher;
import com.tomma8.ledger.event.EmitGate;
import com.tomma8.ledger.event.KafkaEventPublisher;
import com.tomma8.ledger.raft.ConsensusEngine;
import com.tomma8.ledger.raft.LedgerRaftStateMachine;
import com.tomma8.ledger.raft.NodeRole;
import com.tomma8.ledger.raft.RaftNodeManager;
import com.tomma8.ledger.raft.ratis.RatisLedgerStateMachine;
import com.tomma8.ledger.raft.ratis.RatisNodeManager;
import com.tomma8.ledger.rocksdb.OutboxStore;
import com.tomma8.ledger.rocksdb.RocksDBManager;
import com.tomma8.ledger.service.*;
import com.tomma8.ledger.rest.config.properties.LedgerProperties;
import com.tomma8.ledger.rest.config.properties.OutboxProperties;
import com.tomma8.ledger.rest.controller.ClusterController;
import com.tomma8.ledger.statemachine.LedgerStateMachine;
import com.tomma8.ledger.queue.AccountQueueManager;
import com.tomma8.ledger.queue.CommandQueueManager;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration
public class LedgerConfig {

    private static final Logger log = LoggerFactory.getLogger(LedgerConfig.class);

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> ledgerMetricsCustomizer() {
        return registry -> {
            registry.config().meterFilter(new MeterFilter() {
                @Override
                public DistributionStatisticConfig configure(io.micrometer.core.instrument.Meter.Id id, DistributionStatisticConfig config) {
                    if (id.getName().startsWith("ledger.")) {
                        // Timer base unit is NANOSECONDS (not seconds). The
                        // SLO doubles are in seconds (the Prometheus
                        // convention). Without explicit boundaries the
                        // default exponential buckets (1ns..10ns range)
                        // sit far below posting latencies (1–15 ms) and
                        // every sample collapses into the +Inf bucket,
                        // producing a meaningless p95 of "0.01 ms".
                        return DistributionStatisticConfig.builder()
                                .percentilesHistogram(true)
                                .minimumExpectedValue(100_000.0)         // 100 µs in ns
                                .maximumExpectedValue(10_000_000_000.0)  // 10 s in ns
                                .serviceLevelObjectives(
                                        0.0001,   // 100 µs
                                        0.0005,   // 500 µs
                                        0.001,    //   1 ms
                                        0.002,    //   2 ms
                                        0.003,    //   3 ms (NFR p95 target)
                                        0.005,    //   5 ms
                                        0.01,     //  10 ms
                                        0.03,     //  30 ms
                                        0.1,      // 100 ms
                                        0.5,      // 500 ms
                                        1.0,      //   1 s
                                        5.0,      //   5 s
                                        10.0      //  10 s
                                )
                                .build();
                    }
                    return config;
                }
            });
            // Initialise ledger-core hot-path timers as soon as the registry is
            // available so they appear in /actuator/prometheus from boot.
            com.tomma8.ledger.metrics.LedgerMetrics.init(registry);
        };
    }

    @Bean
    public NodeRole nodeRole(LedgerProperties ledgerProps, org.springframework.core.env.Environment env) {
        NodeRole nr = new NodeRole();
        String nodeId = ledgerProps.getNode().getId();
        String nodeName = (nodeId != null && !nodeId.isBlank()) ? nodeId : "standalone";
        String peers = ledgerProps.getRaft().getPeers();
        if (env.acceptsProfiles(org.springframework.core.env.Profiles.of("test"))
                || peers == null || peers.isBlank()) {
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
    public RocksDBManager rocksDBManager(LedgerProperties ledgerProps, MeterRegistry meterRegistry) {
        // Init ledger-core hot-path timers (idempotent).
        com.tomma8.ledger.metrics.LedgerMetrics.init(meterRegistry);
        var rocks = ledgerProps.getRocksdb();
        RocksDBManager mgr = new RocksDBManager(
                rocks.getPath(), rocks.getCacheMb(), rocks.getWriteBufferMb(), rocks.isFsync());
        try {
            mgr.open();
            log.info("RocksDB opened at {}", rocks.getPath());
        } catch (Exception e) {
            log.error("Failed to open RocksDB at {} — running in-memory only", rocks.getPath(), e);
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
    public KafkaEventPublisher kafkaEventPublisher(OutboxStore outboxStore,
                                                   LedgerProperties ledgerProps,
                                                   OutboxProperties outboxProps) {
        // Kafka is detected at runtime. The KafkaProducer constructor resolves bootstrap.servers DNS
        // eagerly and THROWS if the host is unresolvable (broker truly absent, e.g. run without the
        // kafka container).
        //
        // ledger.kafka.required controls what happens then:
        //   false (default; dev/local) → degrade to no-Kafka mode.
        //   true  (production) → FAIL STARTUP. Set in application-prod.yml.
        boolean required = ledgerProps.getKafka().isRequired();
        String brokers = ledgerProps.getKafka().getBootstrapServers();
        int queueCapacity = outboxProps.getAsync().getQueueCapacity();
        int drainThreads = outboxProps.getAsync().getDrainThreads();
        String clientIdSuffix = resolveKafkaClientIdSuffix(ledgerProps);
        try {
            KafkaEventPublisher publisher = new KafkaEventPublisher(
                    brokers, "ledger.balance.change.v1", "ledger.account.v1",
                    queueCapacity, drainThreads, clientIdSuffix);
            publisher.setOutboxStore(outboxStore);
            return publisher;
        } catch (Exception e) {
            if (required) {
                throw new IllegalStateException(
                        "Kafka is required (ledger.kafka.required=true) but unavailable (KAFKA_BOOTSTRAP_SERVERS="
                                + brokers + "). Failing startup. Cause: " + e, e);
            }
            log.warn("Kafka unavailable (KAFKA_BOOTSTRAP_SERVERS={}) — running without Kafka (ledger.kafka.required=false). "
                    + "Writes + live reads work via Raft; projection not produced while absent. Cause: {}",
                    brokers, e.toString());
            return null;
        }
    }

    @Bean(destroyMethod = "close")
    @Profile("!test")
    public AsyncOutboxPublisher asyncOutboxPublisher(OutboxStore outboxStore,
                                                      @org.springframework.beans.factory.annotation.Autowired(required = false) KafkaEventPublisher kafkaPublisher,
                                                      LedgerStateMachine ledgerStateMachine,
                                                      MeterRegistry meterRegistry,
                                                      OutboxProperties outboxProps) {
        Duration pollInterval = Duration.ofSeconds(outboxProps.getPollIntervalSecs());
        int batchSize = outboxProps.getBatchSize();
        AsyncOutboxPublisher publisher = new AsyncOutboxPublisher(
                outboxStore, kafkaPublisher, ledgerStateMachine.getEmitGate(), pollInterval, batchSize);

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

        // Async tier-1 in-process queue gauges — read directly from the
        // publisher instance. Visible only when KafkaEventPublisher is wired
        // (i.e. the broker is up). 0 in no-Kafka mode is fine.
        if (kafkaPublisher != null) {
            Gauge.builder("ledger.outbox.async.queue_depth", kafkaPublisher::getQueueDepth)
                    .description("In-process async outbox queue depth (FSM-apply → drain-thread handoff)")
                    .register(meterRegistry);
            Gauge.builder("ledger.outbox.async.queue_peak", kafkaPublisher::getQueuePeakDepth)
                    .description("Peak in-process async outbox queue depth since startup")
                    .register(meterRegistry);
            Gauge.builder("ledger.outbox.async.enqueued", kafkaPublisher::getEnqueuedCount)
                    .description("Total envelopes enqueued onto the in-process async outbox")
                    .register(meterRegistry);
            Gauge.builder("ledger.outbox.async.drained", kafkaPublisher::getDrainedCount)
                    .description("Total envelopes handed to Kafka producer by drain threads")
                    .register(meterRegistry);
            Gauge.builder("ledger.outbox.async.dropped_sync", kafkaPublisher::getDroppedToSyncCount)
                    .description("Backpressure events: queue full → fell back to sync send on FSM-apply thread")
                    .register(meterRegistry);
            Gauge.builder("ledger.outbox.async.drain_errors", kafkaPublisher::getDrainErrorCount)
                    .description("Drain-thread unexpected errors")
                    .register(meterRegistry);
        }

        log.info("AsyncOutboxPublisher wired with pollInterval={} batchSize={}", pollInterval, batchSize);
        return publisher;
    }

    /**
     * Watcher: polls nodeRole.isLeader() and flips LedgerStateMachine's EmitGate.
     * Gate is owned by the state machine (so test path stays unchanged); this watcher
     * just toggles it. Closed by default; opened the moment this node becomes leader.
     */
    @Bean(destroyMethod = "shutdown")
    public EmitGateWatcher emitGateWatcher(LedgerStateMachine ledgerStateMachine, NodeRole nodeRole) {
        return new EmitGateWatcher(ledgerStateMachine.getEmitGate(), nodeRole);
    }

    public static class EmitGateWatcher implements AutoCloseable {
        private final EmitGate emitGate;
        private final NodeRole nodeRole;
        private final ScheduledExecutorService scheduler;
        private volatile boolean running = true;

        public EmitGateWatcher(EmitGate emitGate, NodeRole nodeRole) {
            this.emitGate = emitGate;
            this.nodeRole = nodeRole;
            this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "emit-gate-watcher");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
            log.info("EmitGateWatcher started");
        }

        private void tick() {
            if (!running) return;
            try {
                boolean shouldEmit = nodeRole.isLeader();
                if (emitGate.isEnabled() != shouldEmit) {
                    emitGate.setEnabled(shouldEmit);
                    log.info("EmitGate flipped: enabled={} (role={})",
                            shouldEmit, shouldEmit ? "LEADER" : "FOLLOWER");
                }
            } catch (Exception e) {
                log.warn("EmitGateWatcher tick failed", e);
            }
        }

        public void shutdown() {
            running = false;
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() { shutdown(); }
    }

    @Bean
    public LedgerStateMachine ledgerStateMachine(
            BalanceStore balanceStore,
            AccountMetaStore accountMetaStore,
            BalanceTypeConfigStore balanceTypeConfigStore,
            @org.springframework.beans.factory.annotation.Autowired(required = false) RocksDBManager rocksDBManager,
            @org.springframework.beans.factory.annotation.Autowired(required = false) OutboxStore outboxStore,
            @org.springframework.beans.factory.annotation.Autowired(required = false) KafkaEventPublisher kafkaPublisher,
            LedgerProperties ledgerProps) {
        Duration idempotencyTtl = ledgerProps.getIdempotency().getTtl();
        LedgerStateMachine sm = new LedgerStateMachine(
                balanceStore, accountMetaStore, balanceTypeConfigStore,
                idempotencyTtl, java.time.Clock.systemUTC(), true);

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
            @org.springframework.beans.factory.annotation.Autowired(required = false) ConsensusEngine raftNodeManager,
            LedgerProperties ledgerProps) {
        return new ClusterController(nodeRole, raftNodeManager, ledgerProps);
    }

    @Bean(destroyMethod = "close")
    public ConsensusEngine raftNodeManager(
            LedgerStateMachine ledgerStateMachine,
            NodeRole nodeRole,
            MeterRegistry meterRegistry,
            LedgerProperties ledgerProps) {
        String nodeId   = ledgerProps.getNode().getId();
        String peers    = ledgerProps.getRaft().getPeers();
        String raftPath = ledgerProps.getRaft().getDataPath();
        int raftPort    = ledgerProps.getRaft().getServerPort();
        String engineRaw = ledgerProps.getRaft().getEngine();
        boolean raftLogSync = ledgerProps.getRaft().isLogFsync();
        String groupId = ledgerProps.getRaft().getGroupId();
        String engine   = (engineRaw == null ? "jraft" : engineRaw).toLowerCase();

        if (nodeId == null || nodeId.isBlank() || peers == null || peers.isBlank()) {
            log.info("Raft cluster not configured — running standalone");
            return null;
        }

        if (engine.equals("ratis")) {
            return buildRatisEngine(ledgerStateMachine, nodeRole, meterRegistry,
                    groupId, nodeId, peers, raftPath, raftPort);
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
        Gauge.builder("ledger.sm.journal.sequence", ledgerStateMachine::getJournalSequence)
                .description("State machine journal sequence (smJournalSeq)")
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
                raftPath, fsm, raftLogSync);
        mgr.init();
        log.info("Raft node started: {} peers={}", serverId, raftPeers);
        return mgr;
    }

    /**
     * Build the Apache Ratis consensus engine (ADR-003 POC). Peer ids default to the
     * docker service hostname (== NODE_ID for self), so the peer string is
     * "host:host:raftPort" per node. Selected via CONSENSUS_ENGINE=ratis.
     */
    private ConsensusEngine buildRatisEngine(
            LedgerStateMachine ledgerStateMachine, NodeRole nodeRole, MeterRegistry meterRegistry,
            String groupId, String nodeId, String peers, String raftPath, int raftPort) {
        RatisLedgerStateMachine fsm = new RatisLedgerStateMachine(ledgerStateMachine, nodeRole);

        StringBuilder ratisPeers = new StringBuilder();
        for (String p : peers.split(",")) {
            String host = p.split(":")[0].trim();
            if (ratisPeers.length() > 0) ratisPeers.append(",");
            ratisPeers.append(host).append(":").append(host).append(":").append(raftPort);
        }

        String ratisDataPath = raftPath + File.separator + "ratis";
        new File(ratisDataPath).mkdirs();
        RatisNodeManager mgr = new RatisNodeManager(
                groupId, nodeId, ratisPeers.toString(), ratisDataPath, raftPort, fsm);
        mgr.init();

        Gauge.builder("ledger.raft.is_leader", () -> mgr.isLeader() ? 1.0 : 0.0)
                .description("Raft leader status: 1 = leader, 0 = follower")
                .tag("node_id", nodeId)
                .register(meterRegistry);
        Gauge.builder("ledger.raft.last_applied_index", mgr::getLastAppliedIndex)
                .description("Raft last applied index (monotonic, per node)")
                .tag("node_id", nodeId)
                .register(meterRegistry);
        Gauge.builder("ledger.sm.journal.sequence", ledgerStateMachine::getJournalSequence)
                .description("State machine journal sequence (smJournalSeq)")
                .tag("node_id", nodeId)
                .register(meterRegistry);

        log.info("Ratis node started: {} peers={} engine=ratis", nodeId, ratisPeers);
        return mgr;
    }

    @Bean(destroyMethod = "close")
    public CommandQueueManager commandQueueManager(
            @org.springframework.beans.factory.annotation.Autowired(required = false) ConsensusEngine raftNodeManager,
            MeterRegistry meterRegistry,
            LedgerProperties ledgerProps) {
        if (raftNodeManager == null) {
            log.info("CommandQueueManager not created — standalone mode");
            return null;
        }
        if (!ledgerProps.getCommandQueue().isEnabled()) {
            log.info("CommandQueueManager disabled via ledger.command-queue.enabled=false");
            return null;
        }
        int maxSize = ledgerProps.getCommandQueue().getMaxSize();
        int batchSize = ledgerProps.getCommandQueue().getBatchSize();
        long batchWaitMs = ledgerProps.getCommandQueue().getBatchWaitMs();
        CommandQueueManager cqm = new CommandQueueManager(raftNodeManager, maxSize, batchSize, batchWaitMs);
        Gauge.builder("ledger.command.queue.depth", cqm::getQueueDepth)
                .description("Depth of global command ingress queue")
                .register(meterRegistry);
        log.info("CommandQueueManager started maxSize={} batchSize={} batchWaitMs={}",
                maxSize, batchSize, batchWaitMs);
        return cqm;
    }

    @Bean(destroyMethod = "close")
    public AccountQueueManager accountQueueManager(
            @org.springframework.beans.factory.annotation.Autowired(required = false) ConsensusEngine raftNodeManager,
            @org.springframework.beans.factory.annotation.Autowired(required = false) CommandQueueManager commandQueueManager,
                                                   MeterRegistry meterRegistry,
                                                   Environment env) {
        if (raftNodeManager == null) {
            log.info("AccountQueueManager not created — standalone mode");
            return null;
        }
        if (commandQueueManager != null) {
            log.info("AccountQueueManager skipped — CommandQueueManager is primary ingress");
            return null;
        }
        AccountQueueManager aqm = new AccountQueueManager(raftNodeManager::submit);

        // Register queue depth gauges for hot accounts (non-prod only — hardcoded
        // IDs include stress fixtures; prod must opt in via config)
        if (!env.acceptsProfiles(Profiles.of("prod"))) {
            Set<String> hotAccounts = Set.of(
                    "STRESS-HOT-CO-001", "COMPANY_FX_ACC", "NOSTRO_USD", "SUSPENSE_USD");
            for (String acc : hotAccounts) {
                Gauge.builder("ledger.account.queue.depth", () -> aqm.getQueueDepth(acc))
                        .description("Depth of pending command queue for account")
                        .tag("accountId", acc)
                        .register(meterRegistry);
            }
            log.info("Hot-account queue gauges registered for {} accounts", hotAccounts.size());
        } else {
            log.info("Hot-account queue gauges skipped in prod profile");
        }
        Gauge.builder("ledger.account.queue.active", () -> aqm.getActiveAccountCount())
                .description("Number of active account queues")
                .register(meterRegistry);

        return aqm;
    }

    @Bean
    CommandLineRunner initDefaultTypes(BalanceTypeConfigStore configStore,
                                        BalanceTypeConfigService configService,
                                        BalanceStore balanceStore,
                                        AccountMetaStore accountMetaStore,
                                        LedgerStateMachine ledgerStateMachine) {
        return args -> {
            // Snapshot restore happens via onSnapshotLoad() from leader transfer
            // during Raft startup. Local RocksDB restore removed — stale local
            // snapshots caused followers to skip replaying log entries that fell
            // in the snapshot boundary gap, producing cross-node balance divergence.

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
            // Persist bootstrap accounts + any initial state to RocksDB snapshot.
            // Without this, bootstrap accounts exist only in-memory and are lost on restart.
            try { ledgerStateMachine.takeSnapshot(); log.info("Bootstrap snapshot saved"); }
            catch (Exception e) { log.warn("Bootstrap snapshot failed (RocksDB may not be open): {}", e.getMessage()); }
        };
    }

    private static String resolveKafkaClientIdSuffix(LedgerProperties ledgerProps) {
        String host = ledgerProps.getNode().getHostname();
        if (host == null || host.isBlank()) {
            host = ledgerProps.getNode().getId();
        }
        if (host == null || host.isBlank()) {
            host = "ledger-node";
        }
        return host;
    }
}

package com.tomma8.ledger.metrics;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

/**
 * Centralised Micrometer wiring for ledger-core hot-path instrumentation.
 *
 * <p>When a {@link MeterRegistry} is wired in (typically by Spring in
 * {@code ledger-restful}), all hot-path code that records timing can use the
 * static {@code record*()} helpers here. If no registry is set, calls are
 * no-ops with effectively zero cost.
 *
 * <p>The Prometheus exporter on the Spring Boot side auto-discovers
 * everything registered here because the global {@code MeterFilter} in
 * {@code LedgerConfig} enables {@code percentilesHistogram} for any meter
 * whose name starts with {@code ledger.}.
 */
public final class LedgerMetrics {

    private static volatile MeterRegistry registry;
    private static volatile Timer applyPersistTimer;
    private static volatile Timer applyDeserializeTimer;
    private static volatile Timer applyEventBuildTimer;
    private static volatile Timer applyTotalTimer;
    private static volatile Timer rocksWriteTimer;
    private static volatile Timer queueWaitTimer;
    private static volatile Timer raftEnqueueTimer;
    private static volatile Timer raftWaitApplyTimer;
    private static volatile Timer raftWakeupTimer;
    private static volatile Timer raftTotalTimer;
    private static volatile DistributionSummary raftBatchSizeSummary;

    private LedgerMetrics() {}

    /**
     * Wire the registry and create meters. Idempotent — call once at startup
     * (typically from {@code LedgerConfig} or {@code LedgerApplication}).
     */
    public static synchronized void init(MeterRegistry reg) {
        if (reg == null || registry == reg) return;
        registry = reg;

        applyPersistTimer = Timer.builder("ledger.apply.persist")
                .description("persistApply: build WriteBatch + RocksDB write with sync=true fsync")
                .publishPercentileHistogram()
                .register(reg);
        applyDeserializeTimer = Timer.builder("ledger.apply.deserialize")
                .description("Apply stage: deserialize command + Journal")
                .publishPercentileHistogram()
                .register(reg);
        applyEventBuildTimer = Timer.builder("ledger.apply.event_build")
                .description("Apply stage: build BalanceChangeEvent list + collect outbox")
                .publishPercentileHistogram()
                .register(reg);
        applyTotalTimer = Timer.builder("ledger.apply.total")
                .description("Apply stage total: deserialize + build events + persistApply")
                .publishPercentileHistogram()
                .register(reg);
        rocksWriteTimer = Timer.builder("ledger.rocksdb.write")
                .description("RocksDB WriteBatch write (includes fsync when sync=true)")
                .publishPercentileHistogram()
                .register(reg);
        queueWaitTimer = Timer.builder("ledger.command.queue.wait")
                .description("Lifecycle: time a command waits in the ingress queue (HTTP submit → worker drain). Grows under saturation when the single dispatcher can't keep up.")
                .publishPercentileHistogram()
                .register(reg);
        raftEnqueueTimer = Timer.builder("ledger.raft.enqueue")
                .description("Raft submit stage S1: enqueue task to Disruptor (may include Raft internal log fsync)")
                .publishPercentileHistogram()
                .register(reg);
        raftWaitApplyTimer = Timer.builder("ledger.raft.wait_apply")
                .description("Raft submit stage S2+S3: wait for onApply() to complete")
                .publishPercentileHistogram()
                .register(reg);
        raftWakeupTimer = Timer.builder("ledger.raft.wakeup")
                .description("Raft submit stage S4: future.get() wakeup + response build")
                .publishPercentileHistogram()
                .register(reg);
        raftTotalTimer = Timer.builder("ledger.raft.total")
                .description("Raft submit total wall time (HTTP handler → response)")
                .publishPercentileHistogram()
                .register(reg);
        raftBatchSizeSummary = DistributionSummary.builder("ledger.raft.batch.size")
                .description("Number of commands per batched Raft log entry")
                .publishPercentileHistogram()
                .register(reg);
    }

    public static void recordApplyDeserialize(long nanos) {
        Timer t = applyDeserializeTimer; if (t != null) t.record(nanos, TimeUnit.NANOSECONDS);
    }
    public static void recordApplyEventBuild(long nanos) {
        Timer t = applyEventBuildTimer; if (t != null) t.record(nanos, TimeUnit.NANOSECONDS);
    }
    public static void recordApplyPersist(long nanos) {
        Timer t = applyPersistTimer; if (t != null) t.record(nanos, TimeUnit.NANOSECONDS);
    }
    public static void recordApplyTotal(long nanos) {
        Timer t = applyTotalTimer; if (t != null) t.record(nanos, TimeUnit.NANOSECONDS);
    }
    public static void recordRocksWrite(long nanos) {
        Timer t = rocksWriteTimer; if (t != null) t.record(nanos, TimeUnit.NANOSECONDS);
    }
    public static void recordQueueWait(long nanos) {
        Timer t = queueWaitTimer; if (t != null) t.record(nanos, TimeUnit.NANOSECONDS);
    }
    public static void recordRaftEnqueue(long nanos) {
        Timer t = raftEnqueueTimer; if (t != null) t.record(nanos, TimeUnit.NANOSECONDS);
    }
    public static void recordRaftWaitApply(long nanos) {
        Timer t = raftWaitApplyTimer; if (t != null) t.record(nanos, TimeUnit.NANOSECONDS);
    }
    public static void recordRaftWakeup(long nanos) {
        Timer t = raftWakeupTimer; if (t != null) t.record(nanos, TimeUnit.NANOSECONDS);
    }
    public static void recordRaftTotal(long nanos) {
        Timer t = raftTotalTimer; if (t != null) t.record(nanos, TimeUnit.NANOSECONDS);
    }
    public static void recordRaftBatchSize(int size) {
        DistributionSummary s = raftBatchSizeSummary; if (s != null) s.record(size);
    }

    public static boolean isEnabled() {
        return registry != null;
    }
}

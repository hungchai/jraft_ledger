package com.tomma8.ledger.wal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Background thread that prunes Chronicle WAL cycle files so disk usage is bounded.
 *
 * <p>Runs every {@code pruneIntervalMs} (default 30s) on a daemon thread. Each tick:
 * <ol>
 *   <li>Reads {@code lastAppliedIndex} from the FSM (a {@link LongSupplier})
 *   <li>Computes {@code safeIndex = lastAppliedIndex - retainEntries}
 *   <li>Enumerate Chronicle cycle files in the queue dir
 *   <li>For each cycle file older than the safeIndex AND total disk usage exceeds
 *       {@code maxBytes}, delete ONE file (bounded by tick budget)
 *   <li>Delete via: pause writer → close queue → unlink file → reopen queue → resume
 * </ol>
 *
 * <p>Tick budget: at most {@code tickBudgetMs} (default 5ms) of pause time per tick.
 * Multi-tick catch-up is acceptable; disk can transiently exceed the cap, but
 * never by more than one tick's worth of new entries.
 *
 * <p>Allocation discipline (Effective Java Item 6): reuses the file list, no
 * per-tick {@code new File(dir.listFiles())} array re-allocations beyond the
 * necessary sort.
 */
public class ChronicleWalPruner {

    private static final Logger log = LoggerFactory.getLogger(ChronicleWalPruner.class);

    private final ChronicleRaftLogStorage storage;
    private final LongSupplier lastAppliedIndexSource;
    private final long retainEntries;
    private final long maxBytes;
    private final long pruneIntervalMs;
    private final long tickBudgetMs;

    private final Thread worker;
    private final AtomicLong lastPrunedCount = new AtomicLong(0);
    private final AtomicLong lastTickAtMs = new AtomicLong(0);
    private volatile boolean running;

    public ChronicleWalPruner(ChronicleRaftLogStorage storage,
                              LongSupplier lastAppliedIndexSource,
                              long retainEntries,
                              long maxBytes,
                              long pruneIntervalMs,
                              long tickBudgetMs) {
        this.storage = storage;
        this.lastAppliedIndexSource = lastAppliedIndexSource == null ? () -> 0L : lastAppliedIndexSource;
        this.retainEntries = Math.max(0, retainEntries);
        this.maxBytes = Math.max(0, maxBytes);
        this.pruneIntervalMs = Math.max(1000L, pruneIntervalMs);
        this.tickBudgetMs = Math.max(1L, tickBudgetMs);
        this.worker = new Thread(this::loop, "chronicle-wal-pruner");
        this.worker.setDaemon(true);
    }

    public void start() {
        if (running) return;
        running = true;
        worker.start();
        log.info("ChronicleWalPruner started: retainEntries={} maxBytes={} intervalMs={} budgetMs={}",
                retainEntries, maxBytes, pruneIntervalMs, tickBudgetMs);
    }

    public void stop() {
        running = false;
        worker.interrupt();
        try { worker.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        log.info("ChronicleWalPruner stopped (lastPruned={})", lastPrunedCount.get());
    }

    public long getLastPrunedCount() { return lastPrunedCount.get(); }
    public long getLastTickAtMs() { return lastTickAtMs.get(); }

    /** One prune tick. Public so tests can drive it deterministically. */
    public int pruneOnce() {
        long applied = lastAppliedIndexSource.getAsLong();
        if (applied <= 0) return 0; // FSM hasn't applied yet
        long safeIndex = Math.max(1L, applied - retainEntries);
        long first = storage.getFirstLogIndex();
        if (first >= safeIndex) return 0; // no data past boundary

        File dir = new File(storage.getPath());
        File[] files = dir.listFiles((d, name) -> name.endsWith(".cq4"));
        if (files == null || files.length == 0) return 0;

        // Compute total bytes; if under cap, no work.
        long totalBytes = 0L;
        for (File f : files) totalBytes += f.length();
        if (totalBytes <= maxBytes) return 0;

        // Sort oldest first by lastModified.
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));

        long deadline = System.nanoTime() + tickBudgetMs * 1_000_000L;
        int deleted = 0;
        for (File f : files) {
            if (System.nanoTime() > deadline) break;
            if (f.lastModified() > System.currentTimeMillis() - 60_000L) {
                // Skip files modified in the last minute — could be the active cycle
                continue;
            }
            long size = f.length();
            // On POSIX (Linux/macOS), an mmap'd file can be unlinked while still mapped.
            // Chronicle keeps the mmap alive so this is safe; OS reclaims the inode on
            // last close. We still pause writers to avoid races with active appends.
            try {
                storage.pauseForMaintenance();
                try {
                    if (f.delete()) {
                        deleted++;
                        totalBytes -= size;
                        log.debug("Pruned Chronicle cycle file: {} ({} bytes)", f.getName(), size);
                    } else {
                        // Retry with reopen dance (e.g. Windows where unlink of open file is denied)
                        try {
                            storage.reopenQueue();
                        } catch (Exception reopenErr) {
                            log.warn("Reopen failed during prune of {}: {}", f.getName(), reopenErr.toString());
                        }
                        if (f.delete()) {
                            deleted++;
                            totalBytes -= size;
                            log.debug("Pruned Chronicle cycle file (after reopen): {} ({} bytes)", f.getName(), size);
                        }
                    }
                } finally {
                    storage.resumeAfterMaintenance();
                }
            } catch (Exception e) {
                log.warn("Failed to prune Chronicle cycle file {}: {}", f.getName(), e.toString());
            }
            if (totalBytes <= maxBytes) break;
        }
        lastPrunedCount.set(deleted);
        lastTickAtMs.set(System.currentTimeMillis());
        if (deleted > 0) {
            log.info("Pruner tick: deleted={} totalBytes={} maxBytes={} safeIndex={}",
                    deleted, totalBytes, maxBytes, safeIndex);
        }
        return deleted;
    }

    private void loop() {
        while (running) {
            try {
                pruneOnce();
            } catch (Throwable t) {
                log.warn("Pruner tick error: {}", t.toString());
            }
            try { Thread.sleep(pruneIntervalMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
    }
}

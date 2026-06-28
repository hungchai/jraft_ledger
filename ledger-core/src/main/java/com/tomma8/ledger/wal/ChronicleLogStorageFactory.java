package com.tomma8.ledger.wal;

import com.alipay.sofa.jraft.option.RaftOptions;
import com.alipay.sofa.jraft.storage.LogStorage;
import com.alipay.sofa.jraft.storage.impl.RocksDBLogStorage;
import net.openhft.chronicle.bytes.SyncMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory that returns either a {@link ChronicleRaftLogStorage} (when enabled)
 * or the SOFAJRaft built-in {@link RocksDBLogStorage} (the default / fallback).
 *
 * <p>Selection is a JVM-level toggle ({@code LEDGER_CHRONICLE_WAL_ENABLED}). When
 * disabled, the system behaves exactly as before — no behavior change. The toggle
 * is intentionally JVM-wide (not per-node) so an entire cluster can be flipped
 * atomically via a config-rolling restart.
 */
public class ChronicleLogStorageFactory {

    private static final Logger log = LoggerFactory.getLogger(ChronicleLogStorageFactory.class);

    private static volatile long currentMaxBytes = 10L * 1024L * 1024L * 1024L; // 10GB default

    public static void setCurrentMaxBytes(long bytes) {
        currentMaxBytes = bytes;
    }

    public static long getCurrentMaxBytes() {
        return currentMaxBytes;
    }

    public static LogStorage create(String dataPath, boolean chronicleEnabled,
                                    SyncMode syncMode, RaftOptions raftOptions,
                                    long maxBytes) {
        setCurrentMaxBytes(maxBytes);
        if (chronicleEnabled) {
            String walPath = dataPath + "/chronicle-wal";
            log.info("Creating ChronicleRaftLogStorage at {} (syncMode={}, maxBytes={})",
                    walPath, syncMode, maxBytes);
            return new ChronicleRaftLogStorage(walPath, syncMode, raftOptions);
        }
        String logPath = dataPath + "/raft_log";
        log.info("Creating RocksDBLogStorage (Chronicle disabled) at {}", logPath);
        return new RocksDBLogStorage(logPath, raftOptions);
    }

    public static SyncMode parseSyncMode(String s) {
        if (s == null) return SyncMode.SYNC;
        try {
            return SyncMode.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown sync mode '{}', falling back to SYNC", s);
            return SyncMode.SYNC;
        }
    }
}

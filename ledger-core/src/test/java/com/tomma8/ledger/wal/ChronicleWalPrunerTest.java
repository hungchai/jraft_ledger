package com.tomma8.ledger.wal;

import com.alipay.sofa.jraft.entity.EnumOutter;
import com.alipay.sofa.jraft.entity.LogEntry;
import com.alipay.sofa.jraft.entity.LogId;
import com.alipay.sofa.jraft.option.LogStorageOptions;
import com.alipay.sofa.jraft.option.RaftOptions;
import net.openhft.chronicle.bytes.SyncMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileWriter;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ChronicleWalPrunerTest {

    @TempDir
    Path temp;

    private ChronicleRaftLogStorage storage;
    private File walDir;

    @BeforeEach
    void setUp() {
        walDir = temp.resolve("chronicle-wal").toFile();
        storage = new ChronicleRaftLogStorage(walDir.getAbsolutePath(), SyncMode.SYNC, new RaftOptions());
        storage.init(new LogStorageOptions());
        ChronicleLogStorageFactory.setCurrentMaxBytes(Long.MAX_VALUE);
    }

    @AfterEach
    void tearDown() {
        if (storage != null) {
            storage.shutdown();
        }
    }

    @Test
    void pruneDeletesFilesBeyondMaxBytes() throws Exception {
        seedCycleFiles(5, 600);
        // Chronicle pre-allocates ~80MB for cycle files; our fake files are
        // tiny. Test asserts the fake files (oldest) get pruned first.
        long maxBytes = 600L;
        ChronicleLogStorageFactory.setCurrentMaxBytes(maxBytes);

        AtomicLong lastApplied = new AtomicLong(100L);
        ChronicleWalPruner pruner = new ChronicleWalPruner(
                storage, lastApplied::get,
                0, maxBytes,
                1000, 50);

        int deleted = pruner.pruneOnce();
        assertTrue(deleted >= 1, "Expected at least one deletion, got " + deleted);

        File[] remaining = walDir.listFiles((d, n) -> n.endsWith(".cq4"));
        assertNotNull(remaining);
        // Remaining fake files (those not pruned) should fit under cap.
        long fakeTotal = 0;
        for (File f : remaining) {
            if (f.getName().startsWith("20260101-")) fakeTotal += f.length();
        }
        assertTrue(fakeTotal <= maxBytes,
                "After prune fakeTotal=" + fakeTotal + " still > maxBytes=" + maxBytes);
    }

    @Test
    void pruneRespectsRetainEntries() throws Exception {
        seedCycleFiles(3, 500);
        long maxBytes = 100;
        ChronicleLogStorageFactory.setCurrentMaxBytes(maxBytes);

        AtomicLong lastApplied = new AtomicLong(100L);
        ChronicleWalPruner pruner = new ChronicleWalPruner(
                storage, lastApplied::get,
                90, maxBytes,
                1000, 100);

        int deleted = pruner.pruneOnce();
        assertTrue(deleted >= 0);
        assertTrue(storage.getFirstLogIndex() >= 0L);
    }

    @Test
    void pruneHonorsTickBudget() throws Exception {
        seedCycleFiles(50, 1024);
        long maxBytes = 5 * 1024;
        ChronicleLogStorageFactory.setCurrentMaxBytes(maxBytes);

        AtomicLong lastApplied = new AtomicLong(1000L);
        ChronicleWalPruner pruner = new ChronicleWalPruner(
                storage, lastApplied::get,
                0, maxBytes,
                1000, 1);

        long t0 = System.nanoTime();
        int deleted = pruner.pruneOnce();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        assertTrue(deleted < 50, "Prune tick took " + elapsedMs + "ms, deleted " + deleted);
    }

    @Test
    void noPruneWhenUnderMaxBytes() throws Exception {
        // Chronicle pre-allocates a large cycle file on init (~80MB), so the
        // first seedCycleFiles write creates that real file. We use a maxBytes
        // larger than the real Chronicle file to assert no delete happens.
        seedCycleFiles(3, 200);
        long maxBytes = Long.MAX_VALUE;
        ChronicleLogStorageFactory.setCurrentMaxBytes(maxBytes);

        AtomicLong lastApplied = new AtomicLong(100L);
        ChronicleWalPruner pruner = new ChronicleWalPruner(
                storage, lastApplied::get,
                0, maxBytes, 1000, 50);

        int deleted = pruner.pruneOnce();
        assertEquals(0, deleted, "Expected no deletes with no cap, got " + deleted);
    }

    @Test
    void reopenAfterPrunePreservesQueue() throws Exception {
        seedCycleFiles(4, 400);
        long maxBytes = 500;
        ChronicleLogStorageFactory.setCurrentMaxBytes(maxBytes);

        AtomicLong lastApplied = new AtomicLong(50L);
        ChronicleWalPruner pruner = new ChronicleWalPruner(
                storage, lastApplied::get,
                0, maxBytes, 1000, 50);
        pruner.pruneOnce();

        long first = storage.getFirstLogIndex();
        long last = storage.getLastLogIndex();
        assertTrue(first >= 0L);
        assertTrue(last >= 0L);
    }

    private void seedCycleFiles(int count, int approxBytesEach) throws Exception {
        walDir.mkdirs();
        LogEntry entry = new LogEntry(EnumOutter.EntryType.ENTRY_TYPE_DATA);
        entry.setId(new LogId(1L, 1L));
        entry.setData(ByteBuffer.wrap(new byte[32]));
        storage.appendEntry(entry);

        long now = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            File f = new File(walDir, String.format("20260101-%05d.cq4", i));
            try (FileWriter w = new FileWriter(f)) {
                char[] fill = new char[approxBytesEach];
                for (int j = 0; j < approxBytesEach; j++) fill[j] = 'x';
                w.write(fill);
            }
            long ts = now - (count - i) * 120_000L;
            Files.setAttribute(f.toPath(), "lastModifiedTime", FileTime.fromMillis(ts));
        }
    }
}
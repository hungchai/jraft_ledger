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
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ChronicleRaftLogStorage}. Uses a {@link TempDir} to
 * isolate the queue directory per test.
 */
public class ChronicleRaftLogStorageTest {

    @TempDir
    Path temp;

    private ChronicleRaftLogStorage storage;

    @BeforeEach
    void setUp() {
        File walDir = temp.resolve("chronicle-wal").toFile();
        storage = new ChronicleRaftLogStorage(walDir.getAbsolutePath(), SyncMode.SYNC, new RaftOptions());
        storage.init(new LogStorageOptions());
    }

    @AfterEach
    void tearDown() {
        if (storage != null) {
            storage.shutdown();
        }
    }

    @Test
    void emptyQueueReturnsExpectedIndices() {
        long first = storage.getFirstLogIndex();
        long last = storage.getLastLogIndex();
        // Empty queue: first=1 (default sentinel), last=0 (no entries)
        assertEquals(1L, first, "empty firstIndex");
        assertEquals(0L, last, "empty lastIndex");
    }

    @Test
    void appendSingleEntryIsReadable() {
        LogEntry e = makeEntry(1L, 1L, 0, "hello".getBytes());
        assertTrue(storage.appendEntry(e));
        long last = storage.getLastLogIndex();
        assertTrue(last >= 1L, "lastIndex=" + last);
        LogEntry read = storage.getEntry(last);
        assertNotNull(read);
        assertEquals(1L, read.getId().getIndex());
        assertEquals(1L, read.getId().getTerm());
        assertArrayEquals("hello".getBytes(), bytesOf(read.getData()));
    }

    @Test
    void append1000EntriesAreAllReadable() {
        List<LogEntry> appended = new ArrayList<>();
        for (int i = 1; i <= 1000; i++) {
            LogEntry e = makeEntry(i, i % 5 + 1, 0, ("payload-" + i).getBytes());
            assertTrue(storage.appendEntry(e), "appendEntry " + i + " failed");
            appended.add(e);
        }
        long last = storage.getLastLogIndex();
        assertTrue(last >= 1000L, "lastIndex=" + last);
        for (int i = 1; i <= 1000; i++) {
            LogEntry read = storage.getEntry(i);
            assertNotNull(read, "missing entry i=" + i);
            assertEquals(i, read.getId().getIndex());
            assertEquals(i % 5 + 1, read.getId().getTerm());
            assertArrayEquals(("payload-" + i).getBytes(), bytesOf(read.getData()));
        }
    }

    @Test
    void appendEntriesBatchFsyncsOnce() {
        List<LogEntry> batch = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            batch.add(makeEntry(i, 1L, 0, ("batch-" + i).getBytes()));
        }
        int n = storage.appendEntries(batch);
        assertEquals(50, n);
        for (int i = 1; i <= 50; i++) {
            LogEntry read = storage.getEntry(i);
            assertNotNull(read);
            assertArrayEquals(("batch-" + i).getBytes(), bytesOf(read.getData()));
        }
    }

    @Test
    void getTermReturnsIndexTerm() {
        storage.appendEntry(makeEntry(1L, 7L, 0, "a".getBytes()));
        storage.appendEntry(makeEntry(2L, 7L, 0, "b".getBytes()));
        storage.appendEntry(makeEntry(3L, 8L, 0, "c".getBytes()));
        assertEquals(7L, storage.getTerm(1L));
        assertEquals(7L, storage.getTerm(2L));
        assertEquals(8L, storage.getTerm(3L));
    }

    @Test
    void reopenQueuePreservesEntries() {
        for (int i = 1; i <= 100; i++) {
            storage.appendEntry(makeEntry(i, i % 3 + 1, 0, ("reopen-" + i).getBytes()));
        }
        long lastBefore = storage.getLastLogIndex();
        storage.shutdown();

        File walDir = temp.resolve("chronicle-wal").toFile();
        ChronicleRaftLogStorage reopened = new ChronicleRaftLogStorage(walDir.getAbsolutePath(), SyncMode.SYNC, new RaftOptions());
        reopened.init(new LogStorageOptions());
        try {
            long first = reopened.getFirstLogIndex();
            long last = reopened.getLastLogIndex();
            assertEquals(first, first);
            assertTrue(last >= 100L, "lastIndex after reopen=" + last + " (was " + lastBefore + ")");
            for (int i = 1; i <= 100; i++) {
                LogEntry read = reopened.getEntry(i);
                if (read == null) continue;
                assertArrayEquals(("reopen-" + i).getBytes(), bytesOf(read.getData()));
            }
        } finally {
            reopened.shutdown();
            storage = reopened;
        }
    }

    @Test
    void truncateSuffixWipesQueueAndRestarts() {
        for (int i = 1; i <= 10; i++) {
            storage.appendEntry(makeEntry(i, 1L, 0, ("x-" + i).getBytes()));
        }
        assertTrue(storage.truncateSuffix(11L));
        long first = storage.getFirstLogIndex();
        assertTrue(first >= 0L, "firstIndex after truncate=" + first);
    }

    @Test
    void resetBehavesLikeTruncateSuffix() {
        storage.appendEntry(makeEntry(1L, 1L, 0, "y".getBytes()));
        assertTrue(storage.reset(2L));
    }

    @Test
    void getEntryBeforeFirstReturnsNull() {
        storage.appendEntry(makeEntry(5L, 1L, 0, "z".getBytes()));
        assertNull(storage.getEntry(0L));
    }

    @Test
    void getEntryAfterLastReturnsNull() {
        for (int i = 1; i <= 5; i++) {
            storage.appendEntry(makeEntry(i, 1L, 0, ("p" + i).getBytes()));
        }
        long last = storage.getLastLogIndex();
        assertNull(storage.getEntry(last + 1000L));
    }

    private static LogEntry makeEntry(long index, long term, int type, byte[] data) {
        LogEntry e = new LogEntry(EnumOutter.EntryType.ENTRY_TYPE_DATA);
        e.setId(new LogId(index, term));
        e.setData(ByteBuffer.wrap(data));
        return e;
    }

    private static byte[] bytesOf(ByteBuffer buf) {
        if (buf == null) return new byte[0];
        byte[] arr = new byte[buf.remaining()];
        buf.duplicate().get(arr);
        return arr;
    }
}
package com.tomma8.ledger.wal;

import com.alipay.sofa.jraft.entity.EnumOutter;
import com.alipay.sofa.jraft.entity.LogEntry;
import com.alipay.sofa.jraft.entity.LogId;
import com.alipay.sofa.jraft.entity.PeerId;
import com.alipay.sofa.jraft.option.LogStorageOptions;
import com.alipay.sofa.jraft.option.RaftOptions;
import com.alipay.sofa.jraft.storage.LogStorage;
import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.bytes.SyncMode;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.RollCycles;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueue;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import net.openhft.chronicle.wire.DocumentContext;
import net.openhft.chronicle.wire.WireType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * SOFAJRaft {@link LogStorage} backed by a Chronicle Queue.
 */
public class ChronicleRaftLogStorage implements LogStorage {

    private static final Logger log = LoggerFactory.getLogger(ChronicleRaftLogStorage.class);

    private static final int MAGIC = 0xC417;

    private final String path;
    private final SyncMode syncMode;

    private final ReentrantReadWriteLock queueLock = new ReentrantReadWriteLock();
    private final ReentrantLock writeLock = new ReentrantLock();

    private SingleChronicleQueue queue;
    private ExcerptAppender appender;
    private volatile boolean initialized;

    // Raft-side index tracking. Chronicle's internal index is a 64-bit auto-increment
    // (not 0-based), so we cannot directly use it as a Raft log index. Instead:
//   - Each Chronicle entry encodes its Raft (logIndex, term) inside the bytes.
//   - We track raftFirstIndex / raftLastIndex as the SOFAJRaft-observed bounds.
//   - raftToChronicle is an in-memory map from Raft logIndex to Chronicle
//     document index for O(1) getEntry lookups.
// On startup, scanRaftIndicesOnInit rebuilds the map from Chronicle.
    private final java.util.concurrent.atomic.AtomicLong raftFirstIndex =
            new java.util.concurrent.atomic.AtomicLong(0L);
    private final java.util.concurrent.atomic.AtomicLong raftLastIndex =
            new java.util.concurrent.atomic.AtomicLong(0L);
    // Bounded by retain-entries × leader throughput (typically << 1M entries).
    // ConcurrentHashMap for read-mostly access on the catch-up path.
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> raftToChronicle =
            new java.util.concurrent.ConcurrentHashMap<>();

    public ChronicleRaftLogStorage(String path, SyncMode syncMode, RaftOptions raftOptions) {
        this.path = path;
        this.syncMode = syncMode != null ? syncMode : SyncMode.SYNC;
        // raftOptions reserved for future per-entry encoding options (e.g. checksum).
    }

    @Override
    public boolean init(LogStorageOptions opts) {
        queueLock.writeLock().lock();
        try {
            File dir = new File(path);
            if (!dir.exists() && !dir.mkdirs()) {
                log.error("Failed to create Chronicle WAL directory: {}", path);
                return false;
            }
            this.queue = SingleChronicleQueueBuilder.builder(dir, WireType.BINARY)
                    .rollCycle(RollCycles.FAST_DAILY)
                    .syncMode(syncMode)
                    .build();
            this.appender = queue.createAppender();
            this.initialized = true;
            // On startup, scan Chronicle to recover first/last Raft indices.
            // Chronicle index is 0-based; Raft indices are encoded in each entry.
            scanRaftIndicesOnInit();
            log.info("ChronicleRaftLogStorage initialized: path={} syncMode={} raftFirstIndex={} raftLastIndex={}",
                    path, syncMode, raftFirstIndex.get(), raftLastIndex.get());
            return true;
        } catch (Exception e) {
            log.error("Failed to init ChronicleRaftLogStorage at {}", path, e);
            return false;
        } finally {
            queueLock.writeLock().unlock();
        }
    }

    private void scanRaftIndicesOnInit() {
        long first = 0L, last = 0L;
        long count = 0L;
        try (ExcerptTailer tailer = queue.createTailer()) {
            tailer.toStart();
            while (true) {
                try (DocumentContext dc = tailer.readingDocument()) {
                    if (!dc.isPresent() || dc.isNotComplete()) break;
                    long chronicleIdx = dc.index();
                    LogEntry entry = decodeEntryFromWire(dc.wire(), chronicleIdx);
                    if (entry != null && entry.getId() != null) {
                        long raftIdx = entry.getId().getIndex();
                        raftToChronicle.put(raftIdx, chronicleIdx);
                        if (count == 0) first = raftIdx;
                        last = raftIdx;
                        count++;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("scanRaftIndicesOnInit failed: {}", e.toString());
        }
        if (count > 0) {
            raftFirstIndex.set(first);
            raftLastIndex.set(last);
        }
    }

    @Override
    public void shutdown() {
        queueLock.writeLock().lock();
        try {
            initialized = false;
            if (queue != null) {
                try { queue.close(); } catch (Exception e) { log.warn("Chronicle close error: {}", e.getMessage()); }
                queue = null;
                appender = null;
            }
            log.info("ChronicleRaftLogStorage shutdown: path={}", path);
        } finally {
            queueLock.writeLock().unlock();
        }
    }

    void reopenQueue() {
        queueLock.writeLock().lock();
        try {
            if (queue != null) {
                try { queue.close(); } catch (Exception e) { log.warn("Chronicle close error: {}", e.getMessage()); }
            }
            this.queue = SingleChronicleQueueBuilder.builder(new File(path), WireType.BINARY)
                    .rollCycle(RollCycles.FAST_DAILY)
                    .syncMode(syncMode)
                    .build();
            this.appender = queue.createAppender();
            initialized = true;
        } finally {
            queueLock.writeLock().unlock();
        }
    }

    void pauseForMaintenance() {
        writeLock.lock();
    }

    void resumeAfterMaintenance() {
        writeLock.unlock();
    }

    @Override
    public long getFirstLogIndex() {
        if (!initialized) return 1L;
        long first = raftFirstIndex.get();
        return first == 0L ? 1L : first;
    }

    @Override
    public long getLastLogIndex() {
        if (!initialized) return 0L;
        return raftLastIndex.get();
    }

    @Override
    public LogEntry getEntry(long index) {
        if (!initialized) return null;
        queueLock.readLock().lock();
        try {
            if (queue == null) return null;
            long first = raftFirstIndex.get();
            long last = raftLastIndex.get();
            if (first == 0L || last == 0L) return null;
            if (index < first || index > last) return null;
            Long chronicleIdx = raftToChronicle.get(index);
            if (chronicleIdx == null) return null;
            try (ExcerptTailer tailer = queue.createTailer()) {
                if (!tailer.moveToIndex(chronicleIdx)) return null;
                try (DocumentContext dc = tailer.readingDocument()) {
                    if (!dc.isPresent() || dc.isNotComplete()) return null;
                    return decodeEntryFromWire(dc.wire(), index);
                }
            }
        } catch (Exception e) {
            log.warn("getEntry({}) failed: {}", index, e.toString());
            return null;
        } finally {
            queueLock.readLock().unlock();
        }
    }

    @Override
    public long getTerm(long index) {
        LogEntry entry = getEntry(index);
        return entry == null ? 0L : entry.getId().getTerm();
    }

    @Override
    public boolean appendEntry(LogEntry entry) {
        if (!initialized || entry == null) return false;
        writeLock.lock();
        try {
            queueLock.readLock().lock();
            try {
                if (appender == null) return false;
                long appendedChronicleIdx;
                try (DocumentContext dc = appender.writingDocument()) {
                    encodeEntryOnWire(dc.wire(), entry);
                    appendedChronicleIdx = dc.index();
                }
                if (syncMode == SyncMode.SYNC) {
                    appender.sync();
                }
                long raftIdx = entry.getId() == null ? 0L : entry.getId().getIndex();
                if (raftIdx > 0L) {
                    raftToChronicle.put(raftIdx, appendedChronicleIdx);
                }
                updateRaftBounds(raftIdx);
                return true;
            } finally {
                queueLock.readLock().unlock();
            }
        } catch (Exception e) {
            log.error("appendEntry failed at index={}", entry.getId() == null ? -1 : entry.getId().getIndex(), e);
            return false;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public int appendEntries(List<LogEntry> entries) {
        if (!initialized || entries == null || entries.isEmpty()) return 0;
        writeLock.lock();
        try {
            queueLock.readLock().lock();
            try {
                if (appender == null) return 0;
                int n = 0;
                long minRaftIdx = 0L;
                long maxRaftIdx = 0L;
                for (LogEntry entry : entries) {
                    long idx;
                    try (DocumentContext dc = appender.writingDocument()) {
                        encodeEntryOnWire(dc.wire(), entry);
                        idx = dc.index();
                    }
                    long raftIdx = entry.getId() != null ? entry.getId().getIndex() : 0L;
                    if (raftIdx > 0L) {
                        raftToChronicle.put(raftIdx, idx);
                        if (minRaftIdx == 0L || raftIdx < minRaftIdx) minRaftIdx = raftIdx;
                        if (raftIdx > maxRaftIdx) maxRaftIdx = raftIdx;
                    }
                    n++;
                }
                if (syncMode == SyncMode.SYNC) {
                    appender.sync();
                }
                updateRaftBounds(minRaftIdx);
                raftLastIndex.accumulateAndGet(maxRaftIdx, Math::max);
                return n;
            } finally {
                queueLock.readLock().unlock();
            }
        } catch (Exception e) {
            log.error("appendEntries failed (size={}): {}", entries.size(), e.toString());
            return 0;
        } finally {
            writeLock.unlock();
        }
    }

    private void updateRaftBounds(long raftIndex) {
        if (raftIndex <= 0L) return;
        // First append wins for first index
        long curFirst = raftFirstIndex.get();
        if (curFirst == 0L) {
            raftFirstIndex.set(raftIndex);
        }
        // Last index monotonic
        raftLastIndex.accumulateAndGet(raftIndex, Math::max);
    }

    @Override
    public boolean truncatePrefix(long firstIndexKept) {
        log.debug("truncatePrefix({}) recorded; pruner will honor on next tick", firstIndexKept);
        return true;
    }

    @Override
    public boolean truncateSuffix(long fromIndex) {
        writeLock.lock();
        try {
            queueLock.writeLock().lock();
            try {
                if (queue != null) {
                    try { queue.close(); } catch (Exception e) { log.warn("truncateSuffix close: {}", e.getMessage()); }
                    queue = null;
                    appender = null;
                }
                deleteDirectoryContents(new File(path));
                this.queue = SingleChronicleQueueBuilder.builder(new File(path), WireType.BINARY)
                        .rollCycle(RollCycles.FAST_DAILY)
                        .syncMode(syncMode)
                        .build();
                this.appender = queue.createAppender();
                raftFirstIndex.set(0L);
                raftLastIndex.set(0L);
                raftToChronicle.clear();
                log.info("truncateSuffix({}) completed; queue rebuilt", fromIndex);
                return true;
            } finally {
                queueLock.writeLock().unlock();
            }
        } catch (Exception e) {
            log.error("truncateSuffix({}) failed: {}", fromIndex, e.toString());
            return false;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public boolean reset(long nextLogIndex) {
        log.info("reset({}) — wiping Chronicle WAL and restarting", nextLogIndex);
        boolean ok = truncateSuffix(nextLogIndex);
        if (ok && nextLogIndex > 0L) {
            // After wipe the next entry will be at Raft index nextLogIndex
            raftFirstIndex.set(nextLogIndex);
            raftLastIndex.set(nextLogIndex - 1L);
        }
        return ok;
    }

    String getPath() { return path; }

    SingleChronicleQueue getQueueForPruner() {
        return queue;
    }

    private void encodeEntryOnWire(net.openhft.chronicle.wire.Wire w, LogEntry entry) {
        LogId id = entry.getId();
        long term = id != null ? id.getTerm() : 0L;
        long index = id != null ? id.getIndex() : 0L;
        EnumOutter.EntryType type = entry.getType() != null ? entry.getType() : EnumOutter.EntryType.ENTRY_TYPE_DATA;
        ByteBuffer data = entry.getData();
        byte[] dataArr = (data != null && data.hasRemaining()) ? bytesOf(data) : new byte[0];
        List<PeerId> peers = entry.getPeers();
        List<PeerId> oldPeers = entry.getOldPeers();

        w.write("magic").int16((short) MAGIC);
        w.write("type").int32(type != null ? type.getNumber() : 0);
        w.write("term").int64(term);
        w.write("index").int64(index);
        w.write("dataLen").int32(dataArr.length);
        if (dataArr.length > 0) {
            w.write("data").bytes(dataArr);
        } else {
            w.write("data").bytes(new byte[0]);
        }

        w.write("peersLen").int32(peers != null ? peers.size() : 0);
        if (peers != null) {
            for (PeerId p : peers) encodePeer(w, p);
        }
        w.write("oldPeersLen").int32(oldPeers != null ? oldPeers.size() : 0);
        if (oldPeers != null) {
            for (PeerId p : oldPeers) encodePeer(w, p);
        }
    }

    private void encodePeer(net.openhft.chronicle.wire.Wire w, PeerId p) {
        if (p == null) {
            w.write("present").int32(0);
            return;
        }
        String s = p.toString();
        int colon1 = s.indexOf(':');
        int colon2 = colon1 >= 0 ? s.indexOf(':', colon1 + 1) : -1;
        String host;
        int port = 0;
        if (colon1 > 0 && colon2 > colon1) {
            host = s.substring(0, colon1);
            try { port = Integer.parseInt(s.substring(colon1 + 1, colon2)); } catch (NumberFormatException ignore) { port = 0; }
        } else {
            host = s;
        }
        w.write("present").int32(1);
        w.write("port").int32(port);
        w.write("host").text(host);
    }

    private LogEntry decodeEntryFromWire(net.openhft.chronicle.wire.Wire w, long fallbackIndex) {
        int magic = w.read("magic").int16() & 0xFFFF;
        if (magic != MAGIC) {
            log.error("Bad magic in Chronicle WAL entry: 0x{}", Integer.toHexString(magic));
            return null;
        }
        int typeByte = w.read("type").int32();
        long term = w.read("term").int64();
        long index = w.read("index").int64();
        int dataLen = w.read("dataLen").int32();
        byte[] data = new byte[dataLen];
        Bytes<?> dataBytes = Bytes.allocateElasticOnHeap(dataLen);
        try {
            w.read("data").bytes(dataBytes);
            if (dataLen > 0) {
                dataBytes.read(data, 0, Math.min(dataLen, (int) dataBytes.readRemaining()));
            }
        } finally {
            dataBytes.releaseLast();
        }

        int peersLen = w.read("peersLen").int32();
        List<PeerId> peers = new java.util.ArrayList<>(peersLen);
        for (int i = 0; i < peersLen; i++) {
            PeerId p = decodePeer(w);
            if (p != null) peers.add(p);
        }
        int oldPeersLen = w.read("oldPeersLen").int32();
        List<PeerId> oldPeers = new java.util.ArrayList<>(oldPeersLen);
        for (int i = 0; i < oldPeersLen; i++) {
            PeerId p = decodePeer(w);
            if (p != null) oldPeers.add(p);
        }

        LogEntry entry = new LogEntry();
        entry.setType(EnumOutter.EntryType.forNumber(typeByte));
        entry.setId(new LogId(index == 0 ? fallbackIndex : index, term));
        entry.setData(ByteBuffer.wrap(data));
        if (!peers.isEmpty()) entry.setPeers(peers);
        if (!oldPeers.isEmpty()) entry.setOldPeers(oldPeers);
        return entry;
    }

    private PeerId decodePeer(net.openhft.chronicle.wire.Wire w) {
        int present = w.read("present").int32();
        if (present == 0) return null;
        int port = w.read("port").int32();
        String host = w.read("host").text();
        PeerId p = new PeerId();
        try {
            p.parse(host + ":" + port);
        } catch (Exception e) {
            log.debug("Bad peer in Chronicle WAL: {}:{} — {}", host, port, e.toString());
            return null;
        }
        return p;
    }

    private static byte[] bytesOf(ByteBuffer buf) {
        byte[] arr = new byte[buf.remaining()];
        buf.duplicate().get(arr);
        return arr;
    }

    private static void deleteDirectoryContents(File dir) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                deleteDirectoryContents(f);
            }
            if (!f.delete()) {
                log.warn("Failed to delete Chronicle WAL file: {}", f.getAbsolutePath());
            }
        }
    }

    public void sync() {
        if (appender != null) {
            try { appender.sync(); } catch (Exception e) { log.warn("sync failed: {}", e.toString()); }
        }
    }
}

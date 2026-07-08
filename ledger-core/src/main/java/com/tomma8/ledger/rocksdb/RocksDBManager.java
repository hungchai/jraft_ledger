package com.tomma8.ledger.rocksdb;

import org.rocksdb.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RocksDBManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RocksDBManager.class);

    // CFs on the sustained write path get the configured (large) memtable; the rest stay
    // small so total memtable RSS stays bounded (memtables are per-CF: budget =
    // writeBufferMb x maxWriteBufferNumber x |hot CFs| + 16MB x 2 x |cold CFs|).
    private static final Set<String> HOT_WRITE_CFS = Set.of(
            ColumnFamilyRegistry.CF_JOURNAL,
            ColumnFamilyRegistry.CF_JOURNAL_LINE,
            ColumnFamilyRegistry.CF_BALANCE,
            ColumnFamilyRegistry.CF_IDEMPOTENCY,
            ColumnFamilyRegistry.CF_OUTBOX);
    private static final long COLD_WRITE_BUF_BYTES = 16L * 1024 * 1024;

    private final String dbPath;
    private final int cacheMb;
    private final int writeBufferMb;
    private final boolean fsync;
    private final int rateLimitMbps;
    private DBOptions dbOptions;               // rebuilt on restoreFromCheckpoint reopen
    private final Map<String, ColumnFamilyHandle> columnFamilyHandles = new HashMap<>();
    private RocksDB rocksDB;
    private WriteOptions writeOptionsInstance;
    private Cache sharedCache;
    private final boolean disableWal;
    private RateLimiter rateLimiter;
    private final List<ColumnFamilyOptions> cfOptionsList = new ArrayList<>();

    // Guards every handle access (get/put/delete/forEach/count/getHandle/direct iterators)
    // against restoreFromCheckpoint's close-wipe-relink-reopen cycle. onApply vs
    // onSnapshotSave/Load are already serialised by LedgerRaftStateMachine.snapshotLock, but
    // that lock is invisible to REST query threads (JournalQueryService -> readJournal/
    // forEachJournal) and the async outbox publisher/drain thread (OutboxStore) — both call
    // into this manager directly. Without this lock, restoreFromCheckpoint can close and
    // free native ColumnFamilyHandle/RocksDB objects while one of those threads is mid-call,
    // which is a JNI use-after-free, not just a catchable exception.
    private final java.util.concurrent.locks.ReentrantReadWriteLock stateLock =
            new java.util.concurrent.locks.ReentrantReadWriteLock();

    public RocksDBManager(String dbPath) {
        this(dbPath, 256, 32, true);
    }

    public RocksDBManager(String dbPath, int cacheMb, int writeBufferMb) {
        this(dbPath, cacheMb, writeBufferMb, true);
    }

    public RocksDBManager(String dbPath, int cacheMb, int writeBufferMb, boolean fsync) {
        this(dbPath, cacheMb, writeBufferMb, fsync, false, 0);
    }

    /**
     * @param disableWal   skip the RocksDB WAL entirely. ONLY safe when an external durable
     *                     log (the Raft WAL) is the authoritative record and RocksDB is a
     *                     deterministic-replay cache: on crash, state replays from the last
     *                     snapshot + raft log. Halves write IO on the data disk.
     * @param rateLimitMbps cap background flush/compaction IO (0 = unlimited). Sustained-write
     *                     forensics: unthrottled flush+compaction bursts (~470MB/s) saturate the
     *                     shared NVMe, device w_await spikes to seconds, and the co-located raft
     *                     log fsync + WriteBatch stall (SLOW_APPLY, elections).
     */
    public RocksDBManager(String dbPath, int cacheMb, int writeBufferMb, boolean fsync,
                          boolean disableWal, int rateLimitMbps) {
        this.dbPath = dbPath;
        this.cacheMb = cacheMb;
        this.writeBufferMb = writeBufferMb;
        this.fsync = fsync;
        this.disableWal = disableWal;
        this.rateLimitMbps = rateLimitMbps;
        this.dbOptions = buildDbOptions();
    }

    private DBOptions buildDbOptions() {
        DBOptions o = new DBOptions()
                .setCreateIfMissing(true)
                .setCreateMissingColumnFamilies(true)
                // Sustained-write soak (5k TPS) showed compaction debt accumulating with the
                // default 2 background jobs: L0 files pile up until write stalls (persist max
                // 1.5s+) and effective throughput decays monotonically. 4 jobs lets flush +
                // compaction run in parallel on the 4-vCPU nodes.
                .setMaxBackgroundJobs(4)
                // Incremental writeback: without this, flushed SST bytes sit dirty in the page
                // cache and the kernel writes them back in giant bursts that block co-located
                // latency-critical writes (raft log fsync).
                .setBytesPerSync(1024 * 1024)
                .setWalBytesPerSync(1024 * 1024)
                // Bound table-reader native memory. Default -1 keeps a reader (index/filter/
                // metadata) open for EVERY SST file forever — measured 476 SSTs after 100min at
                // 3k TPS with container RSS climbing ~20MB/min until the cgroup OOM limit.
                .setMaxOpenFiles(256);
        if (rateLimitMbps > 0) {
            this.rateLimiter = new RateLimiter((long) rateLimitMbps * 1024 * 1024);
            o.setRateLimiter(rateLimiter);
        }
        return o;
    }

    /** Per-CF options sharing one bounded block cache + bounded memtable memory. */
    private ColumnFamilyOptions cfOptions(BlockBasedTableConfig tableConfig, long writeBufBytes, int maxWriteBufferNumber) {
        ColumnFamilyOptions o = new ColumnFamilyOptions()
                .setTableFormatConfig(tableConfig)
                .setWriteBufferSize(writeBufBytes)
                // Hot CFs need >2: with only 1 active + 1 immutable, any flush lag hard-STOPS all
                // writes ("Stopping writes because we have N immutable memtables") — measured 87
                // stalls/40min on [journal] at 3k TPS sustained. 4 lets up to 3 flushes queue.
                .setMaxWriteBufferNumber(maxWriteBufferNumber);
        cfOptionsList.add(o);
        return o;
    }

    public void open() throws Exception {
        RocksDB.loadLibrary();

        File dbDir = new File(dbPath);
        if (!dbDir.exists()) {
            dbDir.mkdirs();
        }

        // Initialize the cached WriteOptions after the JNI library is loaded —
        // constructing a WriteOptions issues a JNI call (newWriteOptions) and
        // would otherwise UnsatisfiedLinkError in <clinit>.
        // fsync=false is safe for dev / read-after-write-replay scenarios where the
        // authoritative log is the Raft WAL (LogStorage) — RocksDB becomes a
        // deterministic-replay cache only. Production MUST keep this true.
        writeOptionsInstance = new WriteOptions().setSync(fsync).setDisableWAL(disableWal);
        log.info("RocksDB WriteOptions.setSync = {}, disableWAL = {}", fsync, disableWal);

        List<ColumnFamilyDescriptor> cfDescriptors = new ArrayList<>();

        // Bound RocksDB off-heap memory so RSS stays flat under sustained write.
        // Without this the block cache + index/filter blocks grow with the DB and
        // eventually trip a cgroup/host RSS OOM-kill (exit 137) — separate from the
        // JVM heap. One shared block cache caps total block-cache RSS across all CFs.
        long cacheBytes = (long) cacheMb * 1024 * 1024;
        long writeBufBytes = (long) writeBufferMb * 1024 * 1024;
        this.sharedCache = new LRUCache(cacheBytes);
        BlockBasedTableConfig tableConfig = new BlockBasedTableConfig()
                .setBlockCache(sharedCache)
                .setCacheIndexAndFilterBlocks(true)                 // count index/filter against the cache (bounded)
                .setPinL0FilterAndIndexBlocksInCache(true);

        // Default CF must be first (cold: nothing hot-path lives in it)
        cfDescriptors.add(new ColumnFamilyDescriptor(
                RocksDB.DEFAULT_COLUMN_FAMILY, cfOptions(tableConfig, COLD_WRITE_BUF_BYTES, 2)));

        // Named column families: hot write-path CFs get the configured buffer + deeper flush
        // queue, others stay small (memtable RSS budget = size x number x |hot CFs|)
        for (String cfName : ColumnFamilyRegistry.allColumnFamilies()) {
            if (cfName.equals(ColumnFamilyRegistry.CF_DEFAULT)) continue;
            boolean hot = HOT_WRITE_CFS.contains(cfName);
            cfDescriptors.add(new ColumnFamilyDescriptor(cfName.getBytes(),
                    cfOptions(tableConfig, hot ? writeBufBytes : COLD_WRITE_BUF_BYTES, hot ? 4 : 2)));
        }

        List<ColumnFamilyHandle> handles = new ArrayList<>();
        rocksDB = RocksDB.open(dbOptions, dbPath, cfDescriptors, handles);

        for (int i = 0; i < handles.size(); i++) {
            String cfName = i == 0
                    ? ColumnFamilyRegistry.CF_DEFAULT
                    : new String(cfDescriptors.get(i).getName());
            columnFamilyHandles.put(cfName, handles.get(i));
        }

        log.info("RocksDB opened at {}, {} column families", dbPath, columnFamilyHandles.size());
    }

    public boolean isOpen() {
        return rocksDB != null && rocksDB.isOwningHandle();
    }

    public ColumnFamilyHandle getHandle(String cfName) {
        ColumnFamilyHandle handle = columnFamilyHandles.get(cfName);
        if (handle == null) {
            throw new IllegalArgumentException("Unknown column family: " + cfName);
        }
        return handle;
    }

    public void put(String cfName, byte[] key, byte[] value) throws Exception {
        stateLock.readLock().lock();
        try {
            rocksDB.put(getHandle(cfName), key, value);
        } finally {
            stateLock.readLock().unlock();
        }
    }

    public byte[] get(String cfName, byte[] key) throws Exception {
        stateLock.readLock().lock();
        try {
            return rocksDB.get(getHandle(cfName), key);
        } finally {
            stateLock.readLock().unlock();
        }
    }

    public void delete(String cfName, byte[] key) throws Exception {
        stateLock.readLock().lock();
        try {
            rocksDB.delete(getHandle(cfName), key);
        } finally {
            stateLock.readLock().unlock();
        }
    }

    /**
     * Iterate every key/value in a column family, in RocksDB key order
     * (deterministic — identical on every node). Used to scan/stream journals
     * and idempotency entries without holding the whole CF in heap.
     */
    public void forEach(String cfName, java.util.function.BiConsumer<byte[], byte[]> consumer) {
        stateLock.readLock().lock();
        try (RocksIterator it = rocksDB.newIterator(getHandle(cfName))) {
            for (it.seekToFirst(); it.isValid(); it.next()) {
                consumer.accept(it.key(), it.value());
            }
        } finally {
            stateLock.readLock().unlock();
        }
    }

    /** Count keys in a column family (CF scan; used for F-006 counts). */
    public long count(String cfName) {
        stateLock.readLock().lock();
        try (RocksIterator it = rocksDB.newIterator(getHandle(cfName))) {
            long n = 0;
            for (it.seekToFirst(); it.isValid(); it.next()) n++;
            return n;
        } finally {
            stateLock.readLock().unlock();
        }
    }

    /**
     * Run {@code action} under the manager's read lock — for callers that need direct
     * {@link #getRocksDB()}/{@link #getHandle} access (e.g. prefix-seek iterators) to stay
     * consistent with {@link #restoreFromCheckpoint}, which holds the write lock for its
     * whole close/wipe/relink/reopen cycle.
     */
    public <T> T readLocked(java.util.function.Supplier<T> action) {
        stateLock.readLock().lock();
        try {
            return action.get();
        } finally {
            stateLock.readLock().unlock();
        }
    }

    /**
     * Cached WriteOptions (initialised in {@link #open()} after the JNI library
     * is loaded). Avoids allocating a new WriteOptions per write — small but
     * real allocation/GC win on the hot path.
     */
    public void write(WriteBatch batch) throws Exception {
        long t0 = System.nanoTime();
        stateLock.readLock().lock();
        try {
            rocksDB.write(writeOptionsInstance, batch);
        } finally {
            stateLock.readLock().unlock();
            com.tomma8.ledger.metrics.LedgerMetrics.recordRocksWrite(System.nanoTime() - t0);
        }
    }

    public RocksDB getRocksDB() {
        return rocksDB;
    }

    /** Flush all memtables to SSTs. Required before {@link #checkpointTo}: with the WAL
     *  disabled a checkpoint only captures flushed data — unflushed memtable contents
     *  would silently miss from the snapshot. */
    public void flushAll() throws Exception {
        try (FlushOptions fo = new FlushOptions().setWaitForFlush(true)) {
            rocksDB.flush(fo, new ArrayList<>(columnFamilyHandles.values()));
        }
    }

    /**
     * Native RocksDB checkpoint into {@code dir} (must not exist): SSTs are HARDLINKED
     * (same filesystem, O(1) IO) + small metadata files copied. Replaces the O(N)
     * journal re-stream that made raft snapshots grow with total history (13GB streams
     * every 600s measured — the sustained-TPS killer).
     */
    public void checkpointTo(java.nio.file.Path dir) throws Exception {
        flushAll();
        try (Checkpoint cp = Checkpoint.create(rocksDB)) {
            cp.createCheckpoint(dir.toString());
        }
    }

    /**
     * Replace the entire DB with a checkpoint's contents: close, wipe the db dir,
     * hardlink the checkpoint files in (fallback to copy across filesystems), reopen.
     * Only called from snapshot load (follower bootstrap / node restart) — never hot path.
     */
    public void restoreFromCheckpoint(java.nio.file.Path checkpointDir) throws Exception {
        stateLock.writeLock().lock();
        try {
            close();
            java.nio.file.Path db = java.nio.file.Path.of(dbPath);
            if (java.nio.file.Files.exists(db)) {
                try (var walk = java.nio.file.Files.walk(db)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                }
            }
            java.nio.file.Files.createDirectories(db);
            try (var files = java.nio.file.Files.list(checkpointDir)) {
                for (java.nio.file.Path src : (Iterable<java.nio.file.Path>) files::iterator) {
                    java.nio.file.Path dst = db.resolve(src.getFileName());
                    try {
                        java.nio.file.Files.createLink(dst, src);
                    } catch (Exception linkFail) {
                        java.nio.file.Files.copy(src, dst);
                    }
                }
            }
            // close() released the option/cache/limiter handles — rebuild before reopening
            this.rateLimiter = null;
            this.dbOptions = buildDbOptions();
            open();
            log.info("RocksDB restored from checkpoint {} and reopened at {}", checkpointDir, dbPath);
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    @Override
    public void close() {
        stateLock.writeLock().lock();
        try {
            for (ColumnFamilyHandle handle : columnFamilyHandles.values()) {
                handle.close();
            }
            columnFamilyHandles.clear();
            if (rocksDB != null) {
                rocksDB.close();
            }
            cfOptionsList.forEach(ColumnFamilyOptions::close);
            cfOptionsList.clear();
            if (sharedCache != null) sharedCache.close();
            if (rateLimiter != null) rateLimiter.close();
            dbOptions.close();
        } finally {
            stateLock.writeLock().unlock();
        }
    }
}

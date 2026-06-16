package com.tomma8.ledger.rocksdb;

import org.rocksdb.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RocksDBManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RocksDBManager.class);

    private final String dbPath;
    private final DBOptions dbOptions;
    private final Map<String, ColumnFamilyHandle> columnFamilyHandles = new HashMap<>();
    private RocksDB rocksDB;
    private WriteOptions writeOptionsInstance;
    private Cache sharedCache;
    private final List<ColumnFamilyOptions> cfOptionsList = new ArrayList<>();

    public RocksDBManager(String dbPath) {
        this.dbPath = dbPath;
        this.dbOptions = new DBOptions()
                .setCreateIfMissing(true)
                .setCreateMissingColumnFamilies(true);
        // Direct I/O bypasses the OS page cache for SST reads + flush/compaction
        // writes. Without it, sustained writes accumulate dirty page cache that
        // the kernel can't reclaim fast enough → the cgroup/pod RSS climbs with DB
        // size and gets OOM-killed (observed: file cache ~3GB driving RSS to the
        // 8g limit). With direct I/O, RSS ≈ anon (bounded heap + block cache), flat
        // under load. Read caching is served by the bounded RocksDB block cache.
        // Default on; disable via LEDGER_ROCKSDB_DIRECT_IO=false if a filesystem
        // doesn't support O_DIRECT (open() falls back automatically on failure).
        if (parseBoolEnv("LEDGER_ROCKSDB_DIRECT_IO", true)) {
            dbOptions.setUseDirectReads(true)
                    .setUseDirectIoForFlushAndCompaction(true);
        }
    }

    private static int parseIntEnv(String k, int def) {
        try { String v = System.getenv(k); return v == null ? def : Integer.parseInt(v.trim()); }
        catch (Exception e) { return def; }
    }

    private static boolean parseBoolEnv(String k, boolean def) {
        String v = System.getenv(k);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }

    /** Per-CF options sharing one bounded block cache + bounded memtable memory. */
    private ColumnFamilyOptions cfOptions(BlockBasedTableConfig tableConfig, long writeBufBytes) {
        ColumnFamilyOptions o = new ColumnFamilyOptions()
                .setTableFormatConfig(tableConfig)
                .setWriteBufferSize(writeBufBytes)
                .setMaxWriteBufferNumber(2);
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
        writeOptionsInstance = new WriteOptions().setSync(true);

        List<ColumnFamilyDescriptor> cfDescriptors = new ArrayList<>();

        // Bound RocksDB off-heap memory so RSS stays flat under sustained write.
        // Without this the block cache + index/filter blocks grow with the DB and
        // eventually trip a cgroup/host RSS OOM-kill (exit 137) — separate from the
        // JVM heap. One shared block cache caps total block-cache RSS across all CFs.
        long cacheBytes = (long) parseIntEnv("LEDGER_ROCKSDB_CACHE_MB", 256) * 1024 * 1024;
        long writeBufBytes = (long) parseIntEnv("LEDGER_ROCKSDB_WRITE_BUFFER_MB", 32) * 1024 * 1024;
        this.sharedCache = new LRUCache(cacheBytes);
        BlockBasedTableConfig tableConfig = new BlockBasedTableConfig()
                .setBlockCache(sharedCache)
                .setCacheIndexAndFilterBlocks(true)                 // count index/filter against the cache (bounded)
                .setPinL0FilterAndIndexBlocksInCache(true);

        // Default CF must be first
        cfDescriptors.add(new ColumnFamilyDescriptor(
                RocksDB.DEFAULT_COLUMN_FAMILY, cfOptions(tableConfig, writeBufBytes)));

        // Named column families
        for (String cfName : ColumnFamilyRegistry.allColumnFamilies()) {
            if (cfName.equals(ColumnFamilyRegistry.CF_DEFAULT)) continue;
            cfDescriptors.add(new ColumnFamilyDescriptor(
                    cfName.getBytes(), cfOptions(tableConfig, writeBufBytes)));
        }

        List<ColumnFamilyHandle> handles = new ArrayList<>();
        try {
            rocksDB = RocksDB.open(dbOptions, dbPath, cfDescriptors, handles);
        } catch (RocksDBException e) {
            // Some filesystems reject O_DIRECT. Fall back to buffered I/O so the
            // node still starts (RSS will rely on cgroup page-cache reclaim).
            if (dbOptions.useDirectReads() || dbOptions.useDirectIoForFlushAndCompaction()) {
                log.warn("RocksDB open with direct I/O failed ({}). Retrying with buffered I/O.", e.getMessage());
                dbOptions.setUseDirectReads(false).setUseDirectIoForFlushAndCompaction(false);
                handles.clear();
                rocksDB = RocksDB.open(dbOptions, dbPath, cfDescriptors, handles);
            } else {
                throw e;
            }
        }

        for (int i = 0; i < handles.size(); i++) {
            String cfName = i == 0
                    ? ColumnFamilyRegistry.CF_DEFAULT
                    : new String(cfDescriptors.get(i).getName());
            columnFamilyHandles.put(cfName, handles.get(i));
        }

        log.info("RocksDB opened at {}, {} column families, directIO reads={}/flushCompaction={}",
                dbPath, columnFamilyHandles.size(),
                dbOptions.useDirectReads(), dbOptions.useDirectIoForFlushAndCompaction());
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
        rocksDB.put(getHandle(cfName), key, value);
    }

    public byte[] get(String cfName, byte[] key) throws Exception {
        return rocksDB.get(getHandle(cfName), key);
    }

    public void delete(String cfName, byte[] key) throws Exception {
        rocksDB.delete(getHandle(cfName), key);
    }

    /** Delete all keys in [beginKey, endKey) from a column family, then compact that
     *  range so the space is reclaimed immediately (DeleteRange alone only writes a
     *  tombstone — disk/RSS would keep growing until a background compaction). endKey
     *  exclusive. Used to prune old journals past the retention window. */
    public void deleteRange(String cfName, byte[] beginKey, byte[] endKey) throws Exception {
        ColumnFamilyHandle cf = getHandle(cfName);
        rocksDB.deleteRange(cf, writeOptionsInstance, beginKey, endKey);
        try (CompactRangeOptions cro = new CompactRangeOptions()
                .setExclusiveManualCompaction(false)) {
            rocksDB.compactRange(cf, beginKey, endKey, cro);
        }
    }

    /**
     * Iterate every key/value in a column family, in RocksDB key order
     * (deterministic — identical on every node). Used to scan/stream journals
     * and idempotency entries without holding the whole CF in heap.
     */
    public void forEach(String cfName, java.util.function.BiConsumer<byte[], byte[]> consumer) {
        try (RocksIterator it = rocksDB.newIterator(getHandle(cfName))) {
            for (it.seekToFirst(); it.isValid(); it.next()) {
                consumer.accept(it.key(), it.value());
            }
        }
    }

    /** Count keys in a column family (CF scan; used for F-006 counts). */
    public long count(String cfName) {
        long n = 0;
        try (RocksIterator it = rocksDB.newIterator(getHandle(cfName))) {
            for (it.seekToFirst(); it.isValid(); it.next()) n++;
        }
        return n;
    }

    /**
     * Cached WriteOptions (initialised in {@link #open()} after the JNI library
     * is loaded). Avoids allocating a new WriteOptions per write — small but
     * real allocation/GC win on the hot path.
     */
    public void write(WriteBatch batch) throws Exception {
        long t0 = System.nanoTime();
        try {
            rocksDB.write(writeOptionsInstance, batch);
        } finally {
            com.tomma8.ledger.metrics.LedgerMetrics.recordRocksWrite(System.nanoTime() - t0);
        }
    }

    public RocksDB getRocksDB() {
        return rocksDB;
    }

    @Override
    public void close() {
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
        dbOptions.close();
    }
}

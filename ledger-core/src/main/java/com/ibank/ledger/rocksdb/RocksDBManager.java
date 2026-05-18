package com.ibank.ledger.rocksdb;

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

    public RocksDBManager(String dbPath) {
        this.dbPath = dbPath;
        this.dbOptions = new DBOptions()
                .setCreateIfMissing(true)
                .setCreateMissingColumnFamilies(true);
    }

    public void open() throws Exception {
        RocksDB.loadLibrary();

        File dbDir = new File(dbPath);
        if (!dbDir.exists()) {
            dbDir.mkdirs();
        }

        List<ColumnFamilyDescriptor> cfDescriptors = new ArrayList<>();

        // Default CF must be first
        cfDescriptors.add(new ColumnFamilyDescriptor(
                RocksDB.DEFAULT_COLUMN_FAMILY, new ColumnFamilyOptions()));

        // Named column families
        for (String cfName : ColumnFamilyRegistry.allColumnFamilies()) {
            if (cfName.equals(ColumnFamilyRegistry.CF_DEFAULT)) continue;
            cfDescriptors.add(new ColumnFamilyDescriptor(
                    cfName.getBytes(), new ColumnFamilyOptions()));
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
        rocksDB.put(getHandle(cfName), key, value);
    }

    public byte[] get(String cfName, byte[] key) throws Exception {
        return rocksDB.get(getHandle(cfName), key);
    }

    public void delete(String cfName, byte[] key) throws Exception {
        rocksDB.delete(getHandle(cfName), key);
    }

    public void write(WriteBatch batch) throws Exception {
        rocksDB.write(new WriteOptions(), batch);
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
        dbOptions.close();
    }
}

package com.example.ledger.config;

import lombok.extern.slf4j.Slf4j;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.springframework.stereotype.Service;

/**
 * RocksDB 服務類 - 公共服務類以供其他包使用
 */
@Service
@Slf4j
public class RocksDBService {
    private final RocksDBConfig config;

    public RocksDBService(RocksDBConfig config) {
        this.config = config;
    }

    public void put(String dbName, byte[] key, byte[] value) throws RocksDBException {
        RocksDB db = config.getRocksDB(dbName);
        db.put(key, value);
    }

    public void put(byte[] key, byte[] value) throws RocksDBException {
        put("default", key, value);
    }

    public byte[] get(String dbName, byte[] key) throws RocksDBException {
        RocksDB db = config.getRocksDB(dbName);
        return db.get(key);
    }

    public byte[] get(byte[] key) throws RocksDBException {
        return get("default", key);
    }

    public void delete(String dbName, byte[] key) throws RocksDBException {
        RocksDB db = config.getRocksDB(dbName);
        db.delete(key);
    }

    public void delete(byte[] key) throws RocksDBException {
        delete("default", key);
    }

    public RocksIterator newIterator(String dbName) throws RocksDBException {
        RocksDB db = config.getRocksDB(dbName);
        return db.newIterator();
    }

    public RocksIterator newIterator() throws RocksDBException {
        return newIterator("default");
    }

    // String-based convenience methods
    public void put(String key, String value) {
        try {
            put(key.getBytes(), value.getBytes());
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to put key: " + key, e);
        }
    }

    public String get(String key) {
        try {
            byte[] result = get(key.getBytes());
            return result != null ? new String(result) : null;
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to get key: " + key, e);
        }
    }

    public void delete(String key) {
        try {
            delete(key.getBytes());
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to delete key: " + key, e);
        }
    }

    /**
     * Get all keys that start with given prefix
     */
    public java.util.List<String> getAllKeysWithPrefix(String prefix) {
        java.util.List<String> matchingKeys = new java.util.ArrayList<>();
        try (RocksIterator iterator = newIterator()) {
            iterator.seekToFirst();
            while (iterator.isValid()) {
                String key = new String(iterator.key());
                if (key.startsWith(prefix)) {
                    matchingKeys.add(key);
                }
                iterator.next();
            }
        } catch (Exception e) {
            log.error("Failed to scan keys with prefix: " + prefix, e);
        }
        return matchingKeys;
    }
} 
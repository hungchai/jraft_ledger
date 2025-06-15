package com.example.ledger.config;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Configuration
@Slf4j
public class RocksDBConfig {

    @Value("${raft.rocksdb.path:./rocksdb_data}")
    private String rocksdbPath;

    @Value("${raft.rocksdb.options.create-if-missing:true}")
    private boolean createIfMissing;

    @Value("${raft.rocksdb.options.max-open-files:1000}")
    private int maxOpenFiles;

    @Value("${raft.rocksdb.options.write-buffer-size:67108864}")
    private long writeBufferSize;

    @Value("${raft.rocksdb.options.max-write-buffer-number:3}")
    private int maxWriteBufferNumber;

    private final ConcurrentMap<String, RocksDB> rocksDBInstances = new ConcurrentHashMap<>();

    static {
        // 載入 RocksDB 原生函式庫
        RocksDB.loadLibrary();
    }

    /**
     * 創建 RocksDB 實例
     */
    @Bean
    public RocksDB rocksDB() throws RocksDBException {
        return getRocksDB("default");
    }

    /**
     * 獲取或創建指定名稱的 RocksDB 實例
     */
    public RocksDB getRocksDB(String dbName) throws RocksDBException {
        return rocksDBInstances.computeIfAbsent(dbName, name -> {
            try {
                Options options = createRocksDBOptions();
                String dbPath = rocksdbPath + "/" + name;
                RocksDB db = RocksDB.open(options, dbPath);
                log.info("RocksDB opened successfully at path: {}", dbPath);
                return db;
            } catch (RocksDBException e) {
                log.error("Failed to open RocksDB at path: {}/{}", rocksdbPath, name, e);
                throw new RuntimeException("Failed to initialize RocksDB", e);
            }
        });
    }

    /**
     * 創建 RocksDB 選項配置
     */
    private Options createRocksDBOptions() {
        Options options = new Options();
        
        // 基本配置
        options.setCreateIfMissing(createIfMissing);
        options.setMaxOpenFiles(maxOpenFiles);
        options.setWriteBufferSize(writeBufferSize);
        options.setMaxWriteBufferNumber(maxWriteBufferNumber);
        
        // 壓縮配置
        options.setCompressionType(CompressionType.LZ4_COMPRESSION);
        options.setBottommostCompressionType(CompressionType.ZSTD_COMPRESSION);
        
        // 性能優化
        options.setIncreaseParallelism(Runtime.getRuntime().availableProcessors());
        options.setAllowConcurrentMemtableWrite(true);
        options.setEnableWriteThreadAdaptiveYield(true);
        
        // 統計信息
        options.setStatistics(new Statistics());
        
        // 日誌配置
        options.setInfoLogLevel(InfoLogLevel.INFO_LEVEL);
        
        return options;
    }

    /**
     * 關閉所有 RocksDB 實例
     */
    @PreDestroy
    public void cleanup() {
        rocksDBInstances.forEach((name, db) -> {
            try {
                db.close();
                log.info("RocksDB instance '{}' closed successfully", name);
            } catch (Exception e) {
                log.error("Error closing RocksDB instance '{}'", name, e);
            }
        });
        rocksDBInstances.clear();
    }
} 
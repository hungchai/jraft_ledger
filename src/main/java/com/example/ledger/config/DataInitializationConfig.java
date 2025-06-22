package com.example.ledger.config;

import com.example.ledger.raft.RaftNodeManager;
import com.example.ledger.service.RocksDBInitializationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Configuration for data initialization from MySQL to RocksDB
 * 
 * IMPORTANT: 
 * - Only the LEADER should initialize data from MySQL
 * - Followers get data through JRaft log replication
 * - New nodes joining existing cluster should NOT initialize from MySQL
 */
@Slf4j
@Configuration
public class DataInitializationConfig {

    @Value("${app.data-initialization.enabled:true}")
    private boolean dataInitializationEnabled;
    
    @Value("${app.data-initialization.leader-only:true}")
    private boolean leaderOnlyInitialization;

    @Value("${raft.enabled:false}")
    private boolean raftEnabled;

    @Autowired
    private RocksDBInitializationService rocksDBInitializationService;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ApplicationContext applicationContext;
    
    @Autowired(required = false)
    private RaftNodeManager raftNodeManager;

    @Bean
    public CommandLineRunner initializeRocksDB() {
        return args -> {
            if (!dataInitializationEnabled) {
                log.info("📋 Data initialization is disabled. Skipping RocksDB initialization.");
                return;
            }
            
            if (raftEnabled && leaderOnlyInitialization) {
                log.info("🎯 JRaft mode with leader-only initialization enabled.");
                log.info("🔄 Data initialization will happen when this node becomes leader.");
                log.info("👥 Followers will receive data through JRaft log replication.");
                return;
            }
            
            // Legacy mode: initialize immediately (for single node or non-JRaft)
            log.info("📋 Standalone mode: Initializing RocksDB from MySQL immediately...");
            performDataInitialization();
        };
    }
    
    /**
     * This method is called by the JRaft leader to initialize data
     * Should only be called once by the leader when cluster starts
     */
    public void initializeAsLeader() {
        if (!dataInitializationEnabled) {
            log.info("📋 Data initialization is disabled for leader.");
            return;
        }
        
        log.info("🎖️  LEADER initializing data from MySQL...");
        performDataInitialization();
        log.info("✅ LEADER completed data initialization. Followers will sync via JRaft.");
    }
    
    private void performDataInitialization() {
        // Check MySQL connection before proceeding
        if (!isMySQLAvailable()) {
            log.error("❌ MySQL is not available. Cannot initialize RocksDB from MySQL.");
            if (raftEnabled) {
                log.error("❌ In JRaft mode, leader must have MySQL access for initialization.");
            }
            log.error("❌ Application will now exit.");
            exitApplication(1);
            return;
        }
        
        try {
            rocksDBInitializationService.initializeFromMySQL();
            log.info("✅ Data initialization completed successfully.");
        } catch (Exception e) {
            log.error("❌ Failed to initialize RocksDB from MySQL: {}", e.getMessage(), e);
            log.error("❌ Application will now exit.");
            exitApplication(1);
        }
    }
    
    private boolean isMySQLAvailable() {
        try (Connection conn = dataSource.getConnection()) {
            if (conn.isValid(5)) { // 5 second timeout
                log.info("✅ MySQL connection verified.");
                return true;
            } else {
                log.error("❌ MySQL connection is invalid.");
                return false;
            }
        } catch (SQLException e) {
            log.error("❌ Failed to connect to MySQL: {}", e.getMessage(), e);
            return false;
        }
    }
    
    private void exitApplication(int status) {
        new Thread(() -> {
            try {
                Thread.sleep(500); // Brief delay to allow logs to be written
                SpringApplication.exit(applicationContext, () -> status);
                System.exit(status);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }
} 
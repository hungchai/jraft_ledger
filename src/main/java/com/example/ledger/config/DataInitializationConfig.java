package com.example.ledger.config;

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
 */
@Slf4j
@Configuration
public class DataInitializationConfig {

    @Value("${app.data-initialization.enabled:true}")
    private boolean dataInitializationEnabled;

    @Autowired
    private RocksDBInitializationService rocksDBInitializationService;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ApplicationContext applicationContext;

    @Bean
    public CommandLineRunner initializeRocksDB() {
        return args -> {
            if (dataInitializationEnabled) {
                log.info("Data initialization is enabled. Initializing RocksDB from MySQL...");
                
                // Check MySQL connection before proceeding
                if (!isMySQLAvailable()) {
                    log.error("❌ MySQL is not available. Cannot initialize RocksDB from MySQL.");
                    log.error("❌ Application will now exit.");
                    exitApplication(1);
                    return;
                }
                
                try {
                    rocksDBInitializationService.initializeFromMySQL();
                } catch (Exception e) {
                    log.error("❌ Failed to initialize RocksDB from MySQL: {}", e.getMessage(), e);
                    log.error("❌ Application will now exit.");
                    exitApplication(1);
                }
            } else {
                log.info("Data initialization is disabled. Skipping RocksDB initialization.");
            }
        };
    }
    
    private boolean isMySQLAvailable() {
        try (Connection conn = dataSource.getConnection()) {
            if (conn.isValid(5)) { // 5 second timeout
                log.info("MySQL connection verified.");
                return true;
            } else {
                log.error("MySQL connection is invalid.");
                return false;
            }
        } catch (SQLException e) {
            log.error("Failed to connect to MySQL: {}", e.getMessage(), e);
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
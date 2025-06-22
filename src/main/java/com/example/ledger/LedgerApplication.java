package com.example.ledger;

import com.example.ledger.service.RocksDBInitializationService;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

@Slf4j
@SpringBootApplication(exclude = {
    HibernateJpaAutoConfiguration.class,
    KafkaAutoConfiguration.class
})
@MapperScan("com.example.ledger.mapper")
public class LedgerApplication {
    
    @Value("${app.data-initialization.enabled:true}")
    private boolean dataInitializationEnabled;
    
    @Autowired
    private RocksDBInitializationService rocksDBInitializationService;
    
    @Autowired
    private DataSource dataSource;
    
    private static ConfigurableApplicationContext context;
    
    public static void main(String[] args) {
        // Configure system properties for better shutdown handling
        System.setProperty("spring.main.register-shutdown-hook", "true");
        System.setProperty("logging.register-shutdown-hook", "false");
        
        context = SpringApplication.run(LedgerApplication.class, args);
        
        // Add shutdown hook for graceful shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received. Initiating graceful shutdown...");
            try {
                context.close();
                log.info("Application shutdown completed successfully");
            } catch (Exception e) {
                log.error("Error during application shutdown", e);
            }
        }, "shutdown-hook"));
        
        log.info("Application started successfully. Press Ctrl+C to shutdown gracefully.");
    }
    
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
                SpringApplication.exit(context, () -> status);
                System.exit(status);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }
    
    @Bean
    public ExitCodeGenerator exitCodeGenerator() {
        return () -> 0;
    }
}

package com.example.ledger;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@SpringBootApplication(exclude = {
    HibernateJpaAutoConfiguration.class,
    KafkaAutoConfiguration.class
})
@MapperScan("com.example.ledger.mapper")
public class LedgerApplication {
    
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
    public ExitCodeGenerator exitCodeGenerator() {
        return () -> 0;
    }
}

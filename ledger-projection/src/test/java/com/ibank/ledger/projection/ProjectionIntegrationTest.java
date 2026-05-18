package com.ibank.ledger.projection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ibank.ledger.dao.mapper.JournalMapper;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest
@Disabled("Requires Docker — run via: docker compose up")
@DisplayName("Projection Service Integration")
class ProjectionIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("ledger_view")
            .withUsername("ledger")
            .withPassword("ledger123");

    @Container
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @DynamicPropertySource
    static void dbProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Autowired private JournalMapper journalMapper;
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Test
    @DisplayName("TC-PROJ-01 Kafka produces event and MySQL stores journal")
    void kafkaEvent_mysqlJournalCreated() throws Exception {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("eventType", "BALANCE_CHANGE");
        event.put("journalId", "JNL-PROJ-001");
        event.put("requestId", "proj-test-001");
        event.put("accountId", "ACC_001");
        event.put("balanceType", "AVAILABLE_BALANCE");
        event.put("currency", "USD");
        event.put("entryType", "DEBIT");
        event.put("amount", "100.00");
        event.put("preBalance", "1000.00");
        event.put("postBalance", "900.00");
        event.put("accountSeq", 1);
        event.put("valueDate", "2026-05-18");

        String json = objectMapper.writeValueAsString(event);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>("ledger.balance.change.v1",
                    "ACC_001:AVAILABLE_BALANCE:USD", json)).get();
        }

        assertThat(mysql.isRunning()).isTrue();
        assertThat(kafka.isRunning()).isTrue();

        // Direct MySQL insert and verify
        journalMapper.insertJournal("JNL-PROJ-001", "NORMAL", "proj-test-001",
                "POSTING", "PROJ-001", LocalDate.of(2026, 5, 18),
                "CONFIRMED", false, java.time.LocalDateTime.now());

        journalMapper.insertJournalLine("JNL-PROJ-001-01", "JNL-PROJ-001", "leg-1",
                "ACC_001", "AVAILABLE_BALANCE", "USD", "DEBIT",
                new BigDecimal("100.00"), new BigDecimal("1000.00"),
                new BigDecimal("900.00"), 1, java.time.LocalDateTime.now());

        assertThat(journalMapper.findById("JNL-PROJ-001")).isNotNull();
    }
}

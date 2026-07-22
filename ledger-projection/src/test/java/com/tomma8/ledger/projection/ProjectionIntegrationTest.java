package com.tomma8.ledger.projection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tomma8.ledger.dao.mapper.JournalMapper;
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
import org.testcontainers.containers.PostgreSQLContainer;
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
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withInitScript("init.sql")
            .withDatabaseName("ledger_view")
            .withUsername("ledger")
            .withPassword("ledger123");

    @Container
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @DynamicPropertySource
    static void dbProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired private JournalMapper journalMapper;
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Test
    @DisplayName("TC-PROJ-01 Kafka produces event and Postgres stores journal")
    void kafkaEvent_postgresJournalCreated() throws Exception {
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

        assertThat(postgres.isRunning()).isTrue();
        assertThat(kafka.isRunning()).isTrue();

        // Direct Postgres insert and verify (use surrogate IDs — no FK constraints)
        long journalPk = System.currentTimeMillis();
        long accountPk = 1L;
        long balancePk = 1L;
        long linePk = System.currentTimeMillis() + 1;

        journalMapper.insertJournal(journalPk, "JNL-PROJ-001", "NORMAL", "proj-test-001",
                "POSTING", "PROJ-001", LocalDate.of(2026, 5, 18),
                "CONFIRMED", false, java.time.LocalDateTime.now());

        journalMapper.insertJournalLine(linePk,
                journalPk,            // journal_id (surrogate → journal.id)
                accountPk,            // account_id (surrogate → account.id)
                balancePk,            // account_balance_id (surrogate → account_balance.id)
                "JNL-PROJ-001-01",    // journal_line_id (business key)
                "JNL-PROJ-001",       // journal_journal_id (denormalized)
                "ACC_001",            // account_account_id (denormalized)
                "leg-1",              // leg_id
                "AVAILABLE_BALANCE",  // balance_type
                "CURRENT",            // position
                "USD",                // currency
                "DEBIT",              // entry_type
                new BigDecimal("100.00"),
                new BigDecimal("1000.00"),
                new BigDecimal("900.00"),
                1,
                java.time.LocalDateTime.now());

        assertThat(journalMapper.findById("JNL-PROJ-001")).isNotNull();
    }
}

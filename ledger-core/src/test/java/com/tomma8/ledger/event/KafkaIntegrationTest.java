package com.tomma8.ledger.event;

import com.tomma8.ledger.domain.command.PostingCommand;
import com.tomma8.ledger.domain.model.*;
import com.tomma8.ledger.statemachine.LedgerStateMachine;
import com.tomma8.ledger.store.AccountMetaStore;
import com.tomma8.ledger.store.BalanceStore;
import com.tomma8.ledger.store.BalanceTypeConfigStore;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
@DisplayName("Kafka Event Publishing Integration")
class KafkaIntegrationTest {

    @Container
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    private String topic;
    private BalanceStore balanceStore;
    private AccountMetaStore accountMetaStore;
    private BalanceTypeConfigStore balanceTypeConfigStore;
    private LedgerStateMachine stateMachine;
    private KafkaEventPublisher publisher;

    @BeforeEach
    void setUp() {
        topic = "ledger.balance.change." + UUID.randomUUID().toString().substring(0, 8);

        balanceStore = new BalanceStore();
        accountMetaStore = new AccountMetaStore();
        balanceTypeConfigStore = new BalanceTypeConfigStore();
        stateMachine = new LedgerStateMachine(balanceStore, accountMetaStore, balanceTypeConfigStore);

        balanceTypeConfigStore.put("AVAILABLE_BALANCE", new BalanceTypeConfig(
                "AVAILABLE_BALANCE", false, null, SignConvention.NORMAL_CREDIT, 1));
        accountMetaStore.put("CLIENT_ACC_001", new Account(
                "CLIENT_ACC_001", AccountType.COMPANY, "Client",
                "CUST-001", AccountStatus.ACTIVE, null, Instant.now()));
        balanceStore.put(new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", "USD"),
                new BalanceEntry(new BigDecimal("1000.00"), 0, 1, "", Instant.now()));

        publisher = new KafkaEventPublisher(kafka.getBootstrapServers(), topic);
        stateMachine.setEventListener(publisher);
        // EmitGate is closed by default; tests opt in.
        stateMachine.getEmitGate().setEnabled(true);
    }

    @AfterEach
    void tearDown() {
        publisher.close();
    }

    @Test
    @DisplayName("TC-KAFKA-01 Posting publishes BalanceChangeEvent to Kafka")
    void posting_publishesBalanceChangeEvent_toKafka() throws Exception {
        PostingCommand cmd = new PostingCommand(
                "kafka-req-001", "TEST", "KAFKA-TEST-001", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST", BigDecimal.ONE, "USD", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", EntryType.DEBIT, "Kafka test")
                )))
        );
        stateMachine.applyPosting(cmd);
        publisher.flush();

        // Consume from Kafka
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-group-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(10));

            assertThat(records.count()).isGreaterThanOrEqualTo(1);

            records.forEach(record -> {
                assertThat(record.key()).contains("CLIENT_ACC_001");
                assertThat(record.value()).contains("BALANCE_CHANGE");
                assertThat(record.value()).contains("\"accountSeq\":2");
            });
        }
    }

    @Test
    @DisplayName("TC-KAFKA-02 Multiple postings produce sequential accountSeq in Kafka")
    void multiplePostings_produceSequentialAccountSeq() throws Exception {
        for (int i = 0; i < 5; i++) {
            PostingCommand cmd = new PostingCommand(
                    "kafka-seq-" + i, "TEST", "KAFKA-SEQ", LocalDate.now(),
                    List.of(new PostingCommand.Leg("leg-" + i, "TEST", BigDecimal.ONE, "USD", List.of(
                            new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT",
                                    EntryType.DEBIT, "Seq " + i)
                    )))
            );
            stateMachine.applyPosting(cmd);
        }
        publisher.flush();

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-group-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(10));

            assertThat(records.count()).isEqualTo(5);

            List<Integer> seqs = new ArrayList<>();
            records.forEach(r -> {
                // Extract accountSeq from JSON
                String val = r.value();
                int idx = val.indexOf("\"accountSeq\":");
                if (idx >= 0) {
                    String sub = val.substring(idx + 13);
                    int end = sub.indexOf(",");
                    if (end > 0) seqs.add(Integer.parseInt(sub.substring(0, end).trim()));
                }
            });

            assertThat(seqs).hasSize(5);
            assertThat(seqs).isSorted();
        }
    }
}

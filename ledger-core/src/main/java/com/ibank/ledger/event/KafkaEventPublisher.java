package com.ibank.ledger.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ibank.ledger.domain.event.BalanceChangeEvent;
import com.ibank.ledger.domain.event.LedgerEventListener;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Publishes BalanceChangeEvents to Kafka.
 * Implements LedgerEventListener for hooking into StateMachine.
 */
public class KafkaEventPublisher implements LedgerEventListener {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);
    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final KafkaProducer<String, String> producer;
    private final String topic;

    public KafkaEventPublisher(String bootstrapServers, String topic) {
        this.topic = topic;
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        this.producer = new KafkaProducer<>(props);
    }

    @Override
    public void onEvent(BalanceChangeEvent event) {
        try {
            String key = event.accountId() + ":" + event.balanceType() + ":" + event.currency();
            String value = mapper.writeValueAsString(event);
            ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);
            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    log.error("Failed to publish event {}: {}", event.eventId(), exception.getMessage());
                }
            });
        } catch (Exception e) {
            log.error("Failed to serialize event {}", event.eventId(), e);
        }
    }

    public void flush() {
        producer.flush();
    }

    public void close() {
        producer.close();
    }
}

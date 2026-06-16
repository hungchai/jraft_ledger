package com.tomma8.ledger.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tomma8.ledger.domain.event.AccountCreatedEvent;
import com.tomma8.ledger.domain.event.BalanceChangeEvent;
import com.tomma8.ledger.domain.event.JournalEventEnvelope;
import com.tomma8.ledger.domain.event.LedgerEventListener;
import com.tomma8.ledger.rocksdb.OutboxStore;
import com.tomma8.ledger.util.LedgerMappers;
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
    private static final ObjectMapper mapper = LedgerMappers.get();

    private final KafkaProducer<String, String> producer;
    private final String balanceChangeTopic;
    private final String accountTopic;
    private OutboxStore outboxStore;

    public KafkaEventPublisher(String bootstrapServers, String balanceChangeTopic) {
        this(bootstrapServers, balanceChangeTopic, "ledger.account.v1");
    }

    public KafkaEventPublisher(String bootstrapServers, String balanceChangeTopic, String accountTopic) {
        this.balanceChangeTopic = balanceChangeTopic;
        this.accountTopic = accountTopic;
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        this.producer = new KafkaProducer<>(props);
    }

    public void setOutboxStore(OutboxStore outboxStore) {
        this.outboxStore = outboxStore;
    }

    @Override
    public void onEvent(BalanceChangeEvent event) {
        try {
            String key = event.accountId() + ":" + event.balanceType() + ":" + event.currency();
            String value = mapper.writeValueAsString(event);
            ProducerRecord<String, String> record = new ProducerRecord<>(balanceChangeTopic, key, value);
            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    log.error("Failed to publish balance event {}: {}", event.eventId(), exception.getMessage());
                } else if (outboxStore != null) {
                    outboxStore.markSent(event.eventId());
                }
            });
        } catch (Exception e) {
            log.error("Failed to serialize balance event {}", event.eventId(), e);
        }
    }

    /**
     * Send one Kafka record bundling all line events of a single journal
     * (posting or reversal). The journalId is used as the partition key so
     * all events for one journal land on the same partition and are
     * consumed in order. Downstream projection (ProjectionConsumer) detects
     * the envelope via its {@code type} field and processes the array.
     */
    @Override
    public void onPosting(JournalEventEnvelope envelope) {
        try {
            String key = envelope.journalId();
            String value = mapper.writeValueAsString(envelope);
            ProducerRecord<String, String> record = new ProducerRecord<>(balanceChangeTopic, key, value);
            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    log.error("Failed to publish journal envelope {}: {}",
                            envelope.journalId(), exception.getMessage());
                } else if (outboxStore != null) {
                    outboxStore.markJournalSent(envelope.journalId());
                }
            });
        } catch (Exception e) {
            log.error("Failed to serialize journal envelope {}", envelope.journalId(), e);
        }
    }

    @Override
    public void onAccountCreated(AccountCreatedEvent event) {
        try {
            String value = mapper.writeValueAsString(event);
            ProducerRecord<String, String> record = new ProducerRecord<>(accountTopic, event.accountId(), value);
            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    log.error("Failed to publish account event {}: {}", event.eventId(), exception.getMessage());
                }
            });
        } catch (Exception e) {
            log.error("Failed to serialize account event {}", event.eventId(), e);
        }
    }

    public void flush() {
        producer.flush();
    }

    public void close() {
        producer.close();
    }
}

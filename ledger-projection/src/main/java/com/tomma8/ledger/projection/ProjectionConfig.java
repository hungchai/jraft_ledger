package com.tomma8.ledger.projection;

import com.tomma8.ledger.dao.mapper.BalanceTypeMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import java.time.LocalDateTime;

@Configuration
public class ProjectionConfig {

    // Hand-built factory beans do NOT inherit spring.kafka.listener.concurrency (that only applies
    // to Spring Boot's auto-configured factory). Without setConcurrency() each listener runs a single
    // consumer thread, so all partitions of a topic serialize onto one thread. Bind the same key
    // (env KAFKA_CONSUMER_CONCURRENCY) and apply it explicitly. Spring caps effective concurrency at
    // the partition count, so a value ≥ partitions just leaves spare threads idle.
    @Value("${spring.kafka.listener.concurrency:6}")
    private int listenerConcurrency;

    /**
     * Single-record listener factory for account-created events.
     */
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> singleFactory(
            ConsumerFactory<String, String> consumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(listenerConcurrency);
        // MANUAL + async commit for the same rebalance-tolerance reason as
        // batchFactory below. Account-created events are idempotent
        // (upsert by accountId), so a duplicate delivery after rebalance
        // is safe.
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.getContainerProperties().setSyncCommits(false);
        return factory;
    }

    /**
     * Batch listener factory for the balance-change consumer: delivers a whole
     * poll batch (up to max-poll-records) as a {@code List<String>} so it can be
     * written in one BATCH transaction.
     */
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> batchFactory(
            ConsumerFactory<String, String> consumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(listenerConcurrency);
        factory.setBatchListener(true);
        // MANUAL_IMMEDIATE = sync commit on ack. After rebalance, the old
        // generation's commitSync throws CommitFailedException which Spring
        // surfaces as a batch failure → projection logs "Balance batch
        // projection failed — not acking" even though the DB write succeeded.
        // Async commits (sync=false) avoid that whole class of false failures.
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.getContainerProperties().setSyncCommits(false);
        return factory;
    }

    @Bean
    CommandLineRunner initBalanceTypes(BalanceTypeMapper balanceTypeMapper) {
        return args -> {
            upsertType(balanceTypeMapper, "AVAILABLE_BALANCE", "Available Balance", "Standard available balance", "ASSET", "NORMAL_CREDIT", false, null, true, "MULTI", 1);
            upsertType(balanceTypeMapper, "TRADE_AHEAD_BALANCE", "Trade Ahead Balance", "Pre-authorized negative balance for trading", "LIABILITY", "NORMAL_DEBIT", true, "PRE_AUTHORIZED", false, "MULTI", 1);
            upsertType(balanceTypeMapper, "BROKERAGE_BALANCE", "Brokerage Balance", "Brokerage specific balance", "ASSET", "NORMAL_CREDIT", false, null, true, "MULTI", 1);
        };
    }

    private static void upsertType(BalanceTypeMapper mapper, String code, String name, String desc,
                                    String category, String signConvention, boolean allowNegative,
                                    String negativeSemantics, boolean zeroFloor, String currencyScope, int version) {
        try {
            mapper.upsertType(code, "{\"en\":\"" + name + "\"}", desc, category, "ACTIVE",
                    signConvention, allowNegative, negativeSemantics, zeroFloor, currencyScope,
                    version, "system", LocalDateTime.now(), "Initial projection setup");
        } catch (Exception e) {
            // Idempotent — may already exist
        }
    }
}

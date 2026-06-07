package com.tomma8.ledger.projection;

import com.tomma8.ledger.dao.mapper.BalanceTypeMapper;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import java.time.LocalDateTime;

@Configuration
public class ProjectionConfig {

    /**
     * Single-record listener factory for account-created events.
     */
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> singleFactory(
            ConsumerFactory<String, String> consumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
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
        factory.setBatchListener(true);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
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

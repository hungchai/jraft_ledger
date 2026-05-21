package com.ibank.ledger.projection;

import com.ibank.ledger.dao.mapper.BalanceTypeMapper;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

@Configuration
public class ProjectionConfig {

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

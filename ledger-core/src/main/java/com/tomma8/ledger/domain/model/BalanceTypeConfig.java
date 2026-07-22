package com.tomma8.ledger.domain.model;

import java.util.Objects;

public record BalanceTypeConfig(
        String typeCode,
        boolean allowNegative,
        NegativeSemantics negativeSemantics,
        SignConvention signConvention,
        int configVersion) {

    public BalanceTypeConfig {
        Objects.requireNonNull(typeCode, "typeCode must not be null");
        Objects.requireNonNull(signConvention, "signConvention must not be null");
        if (configVersion < 1) {
            throw new IllegalArgumentException("configVersion must be >= 1");
        }
    }

    public BalanceTypeConfig withIncrementedVersion() {
        return new BalanceTypeConfig(typeCode, allowNegative, negativeSemantics, signConvention, configVersion + 1);
    }
}

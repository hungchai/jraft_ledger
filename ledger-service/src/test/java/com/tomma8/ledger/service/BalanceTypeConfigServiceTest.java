package com.tomma8.ledger.service;

import com.tomma8.ledger.domain.exception.BalanceTypeInactiveException;
import com.tomma8.ledger.domain.exception.BalanceTypeNotFoundException;
import com.tomma8.ledger.domain.exception.DuplicateBalanceTypeException;
import com.tomma8.ledger.domain.model.BalanceTypeConfig;
import com.tomma8.ledger.domain.model.NegativeSemantics;
import com.tomma8.ledger.domain.model.SignConvention;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("BalanceTypeConfigService (F-001)")
class BalanceTypeConfigServiceTest {

    private BalanceTypeConfigService service;

    @BeforeEach
    void setUp() {
        service = new BalanceTypeConfigService();
        service.registerType(new BalanceTypeConfig(
                "AVAILABLE_BALANCE", false, null,
                SignConvention.NORMAL_CREDIT, 1));
        service.registerType(new BalanceTypeConfig(
                "OLD_TYPE", false, null,
                SignConvention.NORMAL_CREDIT, 1));
    }

    @Test
    @DisplayName("TC-F001-01 getConfig existing active type returns config")
    void getConfig_existingActiveType_returnsConfig() {
        BalanceTypeConfig config = service.getConfig("AVAILABLE_BALANCE");

        assertThat(config).isNotNull();
        assertThat(config.typeCode()).isEqualTo("AVAILABLE_BALANCE");
        assertThat(config.allowNegative()).isFalse();
    }

    @Test
    @DisplayName("TC-F001-02 getConfig non-existent type throws BalanceTypeNotFoundException")
    void getConfig_nonExistentType_throwsBalanceTypeNotFoundException() {
        assertThatThrownBy(() -> service.getConfig("UNKNOWN_TYPE"))
                .isInstanceOf(BalanceTypeNotFoundException.class);
    }

    @Test
    @DisplayName("TC-F001-03 getConfig inactive type throws BalanceTypeInactiveException")
    void getConfig_inactiveType_throwsBalanceTypeInactiveException() {
        service.deactivateType("OLD_TYPE");

        assertThatThrownBy(() -> service.getConfig("OLD_TYPE"))
                .isInstanceOf(BalanceTypeInactiveException.class);
    }

    @Test
    @DisplayName("TC-F001-04 registerType new type successfully registered")
    void registerType_newType_successfullyRegistered() {
        service.registerType(new BalanceTypeConfig(
                "BROKERAGE_BALANCE", false, null,
                SignConvention.NORMAL_CREDIT, 1));

        BalanceTypeConfig config = service.getConfig("BROKERAGE_BALANCE");
        assertThat(config).isNotNull();
        assertThat(config.configVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-F001-05 registerType duplicate code throws DuplicateBalanceTypeException")
    void registerType_duplicateCode_throwsDuplicateBalanceTypeException() {
        BalanceTypeConfig duplicate = new BalanceTypeConfig(
                "AVAILABLE_BALANCE", false, null,
                SignConvention.NORMAL_CREDIT, 1);

        assertThatThrownBy(() -> service.registerType(duplicate))
                .isInstanceOf(DuplicateBalanceTypeException.class);
    }

    @Test
    @DisplayName("TC-F001-06 registerType tradeAheadBalance allowNegative=true with sign convention NORMAL_DEBIT")
    void registerType_tradeAheadBalance_allowNegativeTrue_signConventionNormalDebit() {
        service.registerType(new BalanceTypeConfig(
                "TRADE_AHEAD_BALANCE", true, NegativeSemantics.PRE_AUTHORIZED,
                SignConvention.NORMAL_DEBIT, 1));

        BalanceTypeConfig config = service.getConfig("TRADE_AHEAD_BALANCE");
        assertThat(config.allowNegative()).isTrue();
        assertThat(config.negativeSemantics()).isEqualTo(NegativeSemantics.PRE_AUTHORIZED);
    }

    @Test
    @DisplayName("TC-F001-07 updateConfig existing type configVersion incremented")
    void updateConfig_existingType_configVersionIncremented() {
        BalanceTypeConfig newConfig = new BalanceTypeConfig(
                "AVAILABLE_BALANCE", true, NegativeSemantics.OVERDRAFT,
                SignConvention.NORMAL_CREDIT, 99);

        BalanceTypeConfig updated = service.updateConfig("AVAILABLE_BALANCE", newConfig);

        assertThat(updated.configVersion()).isEqualTo(2);
        assertThat(updated.allowNegative()).isTrue();
    }

    @Test
    @DisplayName("TC-F001-08 deactivateType existing type status becomes inactive")
    void deactivateType_existingType_statusBecomesInactive() {
        service.deactivateType("OLD_TYPE");

        assertThatThrownBy(() -> service.getConfig("OLD_TYPE"))
                .isInstanceOf(BalanceTypeInactiveException.class);
    }
}

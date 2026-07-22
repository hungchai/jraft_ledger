package com.tomma8.ledger.service;

import com.tomma8.ledger.domain.exception.BalanceTypeInactiveException;
import com.tomma8.ledger.domain.exception.BalanceTypeNotFoundException;
import com.tomma8.ledger.domain.exception.DuplicateBalanceTypeException;
import com.tomma8.ledger.domain.model.BalanceTypeConfig;

import java.util.concurrent.ConcurrentHashMap;

/**
 * F-001 Balance Type Registry.
 * Pure in-memory registry — no DB dependency on the write path.
 */
public class BalanceTypeConfigService {

    // ACTIVE and INACTIVE are both stored; queries throw on INACTIVE.
    private final ConcurrentHashMap<String, BalanceTypeConfig> registry = new ConcurrentHashMap<>();
    // Tracks which types are inactive (set by deactivateType)
    private final ConcurrentHashMap<String, Boolean> inactiveTypes = new ConcurrentHashMap<>();

    public BalanceTypeConfig getConfig(String typeCode) {
        if (inactiveTypes.containsKey(typeCode)) {
            throw new BalanceTypeInactiveException(typeCode);
        }
        BalanceTypeConfig config = registry.get(typeCode);
        if (config == null) {
            throw new BalanceTypeNotFoundException(typeCode);
        }
        return config;
    }

    public BalanceTypeConfig getConfigOrNull(String typeCode) {
        try {
            return getConfig(typeCode);
        } catch (BalanceTypeNotFoundException | BalanceTypeInactiveException e) {
            return null;
        }
    }

    public void registerType(BalanceTypeConfig config) {
        if (registry.containsKey(config.typeCode())) {
            throw new DuplicateBalanceTypeException(config.typeCode());
        }
        registry.put(config.typeCode(), config);
    }

    public BalanceTypeConfig updateConfig(String typeCode, BalanceTypeConfig newConfig) {
        BalanceTypeConfig existing = registry.get(typeCode);
        if (existing == null) {
            throw new BalanceTypeNotFoundException(typeCode);
        }
        BalanceTypeConfig updated = new BalanceTypeConfig(
                typeCode,
                newConfig.allowNegative(),
                newConfig.negativeSemantics(),
                newConfig.signConvention(),
                existing.configVersion() + 1);
        registry.put(typeCode, updated);
        return updated;
    }

    public void deactivateType(String typeCode) {
        if (!registry.containsKey(typeCode)) {
            throw new BalanceTypeNotFoundException(typeCode);
        }
        inactiveTypes.put(typeCode, true);
    }
}

package com.tomma8.ledger.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Shared Jackson ObjectMappers.
 *
 * <p>All hot-path serialization sites use a single pre-configured mapper
 * (JavaTimeModule + WRITE_DATES_AS_TIMESTAMPS disabled) instead of constructing
 * per-class instances. Construction is lazy to keep class loading cheap.
 */
public final class LedgerMappers {

    private static final ObjectMapper SHARED = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private LedgerMappers() {}

    public static ObjectMapper get() {
        return SHARED;
    }
}

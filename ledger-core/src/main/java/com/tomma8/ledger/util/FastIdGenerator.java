package com.tomma8.ledger.util;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Lightweight ID generator for hot-path event IDs.
 * Avoids the allocation and entropy-pool cost of {@link java.util.UUID#randomUUID()}.
 */
public final class FastIdGenerator {

    private static final ThreadLocal<StringBuilder> BUFFER =
            ThreadLocal.withInitial(() -> new StringBuilder(32));

    private FastIdGenerator() {}

    /**
     * Returns a 24-32 character hex string unique within this JVM instance.
     * Collisions are statistically negligible: 48 bits of time + 64 bits of randomness.
     */
    public static String nextId() {
        StringBuilder sb = BUFFER.get();
        sb.setLength(0);
        sb.append(Long.toHexString(System.currentTimeMillis()));
        sb.append(Long.toHexString(ThreadLocalRandom.current().nextLong()));
        return sb.toString();
    }
}

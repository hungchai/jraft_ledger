package com.tomma8.ledger.utils;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Twitter Snowflake-style distributed ID generator.
 *
 * 64-bit layout:
 *   1 bit  sign (always 0)
 *   41 bits timestamp (millis since custom epoch)
 *   10 bits worker ID (0-1023)
 *   12 bits sequence (0-4095)
 *
 * Generates ~4 million IDs per second per worker.
 */
public class SnowflakeIdGenerator {

    private static final Logger log = LoggerFactory.getLogger(SnowflakeIdGenerator.class);

    // Custom epoch: 2026-01-01T00:00:00Z
    private static final long EPOCH_MILLIS = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
            .toInstant().toEpochMilli();

    private static final long WORKER_ID_BITS = 10L;
    private static final long SEQUENCE_BITS = 12L;

    /** Tolerated backwards clock drift (ms). Small NTP corrections (≤ 2 s) wait it
     *  out to keep IDs strictly monotonic — never throw on NTP slew. Larger drifts
     *  are handled the same way (spin until clock catches up) because throwing
     *  here causes downstream callers (e.g. ProjectionWriter batches) to abandon
     *  the entire batch and roll back Kafka offsets, which is worse than a brief
     *  blocking wait. */
    private static final long MAX_BACKWARDS_DRIFT_MS = 2_000L;

    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private static final long MAX_SEQUENCE = ~(-1L << SEQUENCE_BITS);

    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;

    private final long workerId;
    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public SnowflakeIdGenerator(long workerId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId must be between 0 and " + MAX_WORKER_ID);
        }
        this.workerId = workerId;
    }

    public synchronized long nextId() {
        long timestamp = currentTimeMillis();

        if (timestamp < lastTimestamp) {
            long drift = lastTimestamp - timestamp;
            // Always spin-wait instead of throwing. Caller (ProjectionWriter /
            // Outbox publisher) treats nextId() throws as fatal batch failure →
            // rolls back Kafka offsets, amplifies lag. Blocking a few ms here
            // is far cheaper than the cascading redelivery storm.
            if (drift > MAX_BACKWARDS_DRIFT_MS) {
                log.warn("Snowflake clock moved backwards by {} ms — waiting until {}",
                        drift, lastTimestamp);
            }
            timestamp = waitNextMillis(lastTimestamp);
        }

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                // Sequence overflow, wait until next millisecond
                timestamp = waitNextMillis(timestamp);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = timestamp;
        return ((timestamp - EPOCH_MILLIS) << TIMESTAMP_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    private long waitNextMillis(long lastTs) {
        long ts = currentTimeMillis();
        while (ts <= lastTs) {
            ts = currentTimeMillis();
        }
        return ts;
    }

    protected long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    public static SnowflakeIdGenerator forWorker(long workerId) {
        return new SnowflakeIdGenerator(workerId);
    }

    /**
     * Derive a worker ID from environment or fallback to a hash of the hostname.
     */
    public static long deriveWorkerId() {
        String env = System.getenv("SNOWFLAKE_WORKER_ID");
        if (env != null) {
            try {
                return Long.parseLong(env) & MAX_WORKER_ID;
            } catch (NumberFormatException ignored) {
            }
        }
        try {
            String host = java.net.InetAddress.getLocalHost().getHostName();
            return (host.hashCode() & 0x7FFFFFFF) % (MAX_WORKER_ID + 1);
        } catch (Exception e) {
            return 0L;
        }
    }
}

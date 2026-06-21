package com.tomma8.ledger.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class SnowflakeIdGeneratorTest {

    @Test
    void nextId_generatesMonotonicallyIncreasingIds() {
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(1);
        long id1 = gen.nextId();
        long id2 = gen.nextId();
        long id3 = gen.nextId();
        assertThat(id1).isPositive();
        assertThat(id2).isGreaterThan(id1);
        assertThat(id3).isGreaterThan(id2);
    }

    @Test
    void nextId_toleratesSmallBackwardsClockDrift() {
        SnowflakeIdGenerator gen = new BackwardsClockGenerator(1, 3);
        long id1 = gen.nextId(); // lastTimestamp = T
        // Next call sees clock at T-3 ms (simulated NTP adjustment)
        long id2 = gen.nextId();
        assertThat(id2).isGreaterThan(id1);
    }

    @Test
    void nextId_staysMonotonicOnLargeBackwardsClockDrift() {
        // Large backward jump (e.g. NTP step, VM pause) must NOT throw — the
        // generator pins to lastTimestamp and keeps issuing monotonic IDs.
        SnowflakeIdGenerator gen = new BackwardsClockGenerator(1, 250);
        long id1 = gen.nextId(); // lastTimestamp = T
        // Next call sees clock at T-250 ms — must still advance, not throw.
        long id2 = gen.nextId();
        assertThat(id2).isGreaterThan(id1);
        long id3 = gen.nextId();
        assertThat(id3).isGreaterThan(id2);
    }

    @Test
    void nextId_sequenceRolloverWithinSameMillisecond() {
        // 12-bit sequence: at most 4096 IDs per millisecond; more would spin in waitNextMillis.
        SnowflakeIdGenerator gen = new FixedTimestampGenerator(1, System.currentTimeMillis());
        long previous = 0;
        for (int i = 0; i < 4096; i++) {
            long id = gen.nextId();
            assertThat(id).isGreaterThan(previous);
            previous = id;
        }
    }

    /** Generator that simulates a backwards clock jump on the 2nd call,
     *  then returns real time on subsequent calls (so waitNextMillis can recover). */
    static class BackwardsClockGenerator extends SnowflakeIdGenerator {
        private final int jumpMs;
        private int callCount = 0;
        private long baseTime;

        BackwardsClockGenerator(long workerId, int jumpMs) {
            super(workerId);
            this.jumpMs = jumpMs;
        }

        @Override
        protected long currentTimeMillis() {
            callCount++;
            if (callCount == 1) {
                baseTime = System.currentTimeMillis();
                return baseTime;
            }
            if (callCount == 2) {
                return baseTime - jumpMs; // simulate backwards jump
            }
            return System.currentTimeMillis(); // recover (real time, moves forward)
        }
    }

    /** Generator that always returns the same timestamp. */
    static class FixedTimestampGenerator extends SnowflakeIdGenerator {
        private final long fixed;

        FixedTimestampGenerator(long workerId, long fixed) {
            super(workerId);
            this.fixed = fixed;
        }

        @Override
        protected long currentTimeMillis() {
            return fixed;
        }
    }
}

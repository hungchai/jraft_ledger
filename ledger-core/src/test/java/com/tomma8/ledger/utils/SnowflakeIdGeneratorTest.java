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
    void nextId_throwsOnLargeBackwardsClockDrift() {
        SnowflakeIdGenerator gen = new BackwardsClockGenerator(1, 10);
        gen.nextId(); // lastTimestamp = T
        assertThatThrownBy(gen::nextId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Clock moved backwards");
    }

    @Test
    void nextId_sequenceRollofeWithinSameMillisecond() {
        SnowflakeIdGenerator gen = new FixedTimestampGenerator(1, System.currentTimeMillis());
        long previous = 0;
        for (int i = 0; i < 5000; i++) {
            long id = gen.nextId();
            assertThat(id).isGreaterThan(previous);
            previous = id;
        }
    }

    /** Generator that simulates a backwards clock jump on the N-th call. */
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
            return baseTime - jumpMs;
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

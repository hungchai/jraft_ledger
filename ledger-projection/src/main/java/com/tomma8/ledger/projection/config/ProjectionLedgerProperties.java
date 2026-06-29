package com.tomma8.ledger.projection.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Type-safe binding for {@code ledger.projection.*} and nested {@code ledger.snowflake.*}
 * used by the projection service.
 */
@ConfigurationProperties(prefix = "ledger")
public class ProjectionLedgerProperties {

    private Projection projection = new Projection();
    private Snowflake snowflake = new Snowflake();

    public Projection getProjection() { return projection; }
    public void setProjection(Projection projection) { this.projection = projection; }

    public Snowflake getSnowflake() { return snowflake; }
    public void setSnowflake(Snowflake snowflake) { this.snowflake = snowflake; }

    public static class Projection {
        private Journal journal = new Journal();
        private Balance balance = new Balance();

        public Journal getJournal() { return journal; }
        public void setJournal(Journal journal) { this.journal = journal; }

        public Balance getBalance() { return balance; }
        public void setBalance(Balance balance) { this.balance = balance; }

        public static class Journal {
            private int flushIntervalMs = 50;
            private int maxBuffer = 4000;

            public int getFlushIntervalMs() { return flushIntervalMs; }
            public void setFlushIntervalMs(int flushIntervalMs) { this.flushIntervalMs = flushIntervalMs; }

            public int getMaxBuffer() { return maxBuffer; }
            public void setMaxBuffer(int maxBuffer) { this.maxBuffer = maxBuffer; }
        }

        public static class Balance {
            private Async async = new Async();

            public Async getAsync() { return async; }
            public void setAsync(Async async) { this.async = async; }

            public static class Async {
                private int queueCapacity = 64;
                private long submitTimeoutMs = 2000L;

                public int getQueueCapacity() { return queueCapacity; }
                public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }

                public long getSubmitTimeoutMs() { return submitTimeoutMs; }
                public void setSubmitTimeoutMs(long submitTimeoutMs) { this.submitTimeoutMs = submitTimeoutMs; }
            }
        }
    }

    public static class Snowflake {
        private String workerId = "";

        public String getWorkerId() { return workerId; }
        public void setWorkerId(String workerId) { this.workerId = workerId; }
    }
}

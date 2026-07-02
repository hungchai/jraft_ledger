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
        private Kafka kafka = new Kafka();

        public Journal getJournal() { return journal; }
        public void setJournal(Journal journal) { this.journal = journal; }

        public Balance getBalance() { return balance; }
        public void setBalance(Balance balance) { this.balance = balance; }

        public Kafka getKafka() { return kafka; }
        public void setKafka(Kafka kafka) { this.kafka = kafka; }

        public static class Kafka {
            // Listener concurrency applied to the hand-built factories (Spring Boot's
            // spring.kafka.listener.concurrency only wires the auto-configured factory).
            // Effective concurrency is capped at the topic partition count.
            private int concurrency = 6;

            public int getConcurrency() { return concurrency; }
            public void setConcurrency(int concurrency) { this.concurrency = concurrency; }
        }

        public static class Journal {
            private int flushIntervalMs = 50;
            private int maxBuffer = 4000;
            // projection_event_log is an audit-only trail (never read on the hot path; real
            // idempotency = account_balance seq-guard + journal_line_id UNIQUE). Default OFF —
            // skips one sharded INSERT + UNIQUE-index maintenance per event, the dominant
            // projection-consume bottleneck. Set true only if the audit table is actually needed.
            private boolean eventLogEnabled = false;

            public int getFlushIntervalMs() { return flushIntervalMs; }
            public void setFlushIntervalMs(int flushIntervalMs) { this.flushIntervalMs = flushIntervalMs; }

            public int getMaxBuffer() { return maxBuffer; }
            public void setMaxBuffer(int maxBuffer) { this.maxBuffer = maxBuffer; }

            public boolean isEventLogEnabled() { return eventLogEnabled; }
            public void setEventLogEnabled(boolean eventLogEnabled) { this.eventLogEnabled = eventLogEnabled; }
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

package com.tomma8.ledger.rest.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Type-safe binding for {@code outbox.*} keys in {@code application.yml}.
 */
@ConfigurationProperties(prefix = "outbox")
public class OutboxProperties {

    private int pollIntervalSecs = 10;
    private int batchSize = 100;
    private Async async = new Async();

    public int getPollIntervalSecs() { return pollIntervalSecs; }
    public void setPollIntervalSecs(int pollIntervalSecs) { this.pollIntervalSecs = pollIntervalSecs; }

    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

    public Async getAsync() { return async; }
    public void setAsync(Async async) { this.async = async; }

    public static class Async {
        private int queueCapacity = 10_000;
        private int drainThreads = 2;

        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }

        public int getDrainThreads() { return drainThreads; }
        public void setDrainThreads(int drainThreads) { this.drainThreads = drainThreads; }
    }
}

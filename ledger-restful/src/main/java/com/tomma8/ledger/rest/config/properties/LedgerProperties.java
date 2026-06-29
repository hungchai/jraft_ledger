package com.tomma8.ledger.rest.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Type-safe binding for {@code ledger.*} keys in {@code application.yml}.
 * Do not read these values via {@code Environment.getProperty} in application code.
 */
@ConfigurationProperties(prefix = "ledger")
public class LedgerProperties {

    private Node node = new Node();
    private String advertiseUrl = "";
    private Rocksdb rocksdb = new Rocksdb();
    private Raft raft = new Raft();
    private Kafka kafka = new Kafka();
    private CommandQueue commandQueue = new CommandQueue();
    private Posting posting = new Posting();
    private Idempotency idempotency = new Idempotency();
    private Snowflake snowflake = new Snowflake();

    public Node getNode() { return node; }
    public void setNode(Node node) { this.node = node; }

    public String getAdvertiseUrl() { return advertiseUrl; }
    public void setAdvertiseUrl(String advertiseUrl) { this.advertiseUrl = advertiseUrl; }

    public Rocksdb getRocksdb() { return rocksdb; }
    public void setRocksdb(Rocksdb rocksdb) { this.rocksdb = rocksdb; }

    public Raft getRaft() { return raft; }
    public void setRaft(Raft raft) { this.raft = raft; }

    public Kafka getKafka() { return kafka; }
    public void setKafka(Kafka kafka) { this.kafka = kafka; }

    public CommandQueue getCommandQueue() { return commandQueue; }
    public void setCommandQueue(CommandQueue commandQueue) { this.commandQueue = commandQueue; }

    public Posting getPosting() { return posting; }
    public void setPosting(Posting posting) { this.posting = posting; }

    public Idempotency getIdempotency() { return idempotency; }
    public void setIdempotency(Idempotency idempotency) { this.idempotency = idempotency; }

    public Snowflake getSnowflake() { return snowflake; }
    public void setSnowflake(Snowflake snowflake) { this.snowflake = snowflake; }

    public static class Node {
        private String id = "";
        private String hostname = "";

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getHostname() { return hostname; }
        public void setHostname(String hostname) { this.hostname = hostname; }
    }

    public static class Rocksdb {
        private String path = "/tmp/ledger/rocksdb";
        private int cacheMb = 256;
        private int writeBufferMb = 32;
        private boolean fsync = true;

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }

        public int getCacheMb() { return cacheMb; }
        public void setCacheMb(int cacheMb) { this.cacheMb = cacheMb; }

        public int getWriteBufferMb() { return writeBufferMb; }
        public void setWriteBufferMb(int writeBufferMb) { this.writeBufferMb = writeBufferMb; }

        public boolean isFsync() { return fsync; }
        public void setFsync(boolean fsync) { this.fsync = fsync; }
    }

    public static class Raft {
        private String groupId = "ledger-group-1";
        private String dataPath = "/tmp/ledger/raft";
        private int serverPort = 28080;
        private String peers = "";
        private String engine = "jraft";
        private boolean logFsync = true;

        public String getGroupId() { return groupId; }
        public void setGroupId(String groupId) { this.groupId = groupId; }

        public String getDataPath() { return dataPath; }
        public void setDataPath(String dataPath) { this.dataPath = dataPath; }

        public int getServerPort() { return serverPort; }
        public void setServerPort(int serverPort) { this.serverPort = serverPort; }

        public String getPeers() { return peers; }
        public void setPeers(String peers) { this.peers = peers; }

        public String getEngine() { return engine; }
        public void setEngine(String engine) { this.engine = engine; }

        public boolean isLogFsync() { return logFsync; }
        public void setLogFsync(boolean logFsync) { this.logFsync = logFsync; }
    }

    public static class Kafka {
        private boolean required = false;
        private String bootstrapServers = "localhost:9092";

        public boolean isRequired() { return required; }
        public void setRequired(boolean required) { this.required = required; }

        public String getBootstrapServers() { return bootstrapServers; }
        public void setBootstrapServers(String bootstrapServers) { this.bootstrapServers = bootstrapServers; }
    }

    public static class CommandQueue {
        private boolean enabled = true;
        private int maxSize = 50_000;
        private int batchSize = 16;
        private long batchWaitMs = 1L;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public int getMaxSize() { return maxSize; }
        public void setMaxSize(int maxSize) { this.maxSize = maxSize; }

        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

        public long getBatchWaitMs() { return batchWaitMs; }
        public void setBatchWaitMs(long batchWaitMs) { this.batchWaitMs = batchWaitMs; }
    }

    public static class Posting {
        private int maxInflight = 256;
        private long inflightAcquireMs = 100L;
        private int traceSampleN = 0;

        public int getMaxInflight() { return maxInflight; }
        public void setMaxInflight(int maxInflight) { this.maxInflight = maxInflight; }

        public long getInflightAcquireMs() { return inflightAcquireMs; }
        public void setInflightAcquireMs(long inflightAcquireMs) { this.inflightAcquireMs = inflightAcquireMs; }

        public int getTraceSampleN() { return traceSampleN; }
        public void setTraceSampleN(int traceSampleN) { this.traceSampleN = traceSampleN; }
    }

    public static class Idempotency {
        private java.time.Duration ttl = java.time.Duration.ofDays(30);

        public java.time.Duration getTtl() { return ttl; }
        public void setTtl(java.time.Duration ttl) { this.ttl = ttl; }
    }

    public static class Snowflake {
        private String workerId = "";

        public String getWorkerId() { return workerId; }
        public void setWorkerId(String workerId) { this.workerId = workerId; }
    }
}

package com.tomma8.ledger.rest.config;

import com.tomma8.ledger.config.ConfigService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Spring-backed ConfigService implementation.
 *
 * Reads configuration through Spring's @Value annotation, which merges:
 * 1. Command-line args  (--prop=value)
 * 2. Environment vars   (LEDGER_ROCKSDB_PATH, KAFKA_BOOTSTRAP_SERVERS, etc.)
 * 3. application.yml    (spring resolved, env var override by default)
 *
 * This means existing deployments using LEDGER_ROCKSDB_PATH env var will
 * continue to work — @Value("${key}") reads env vars automatically.
 *
 * Apollo / Nacos migration: replace this class with ApolloConfigService that
 * reads from apollo Config instance. Zero changes to ConfigService callers.
 */
@Service
public class SpringConfigService implements ConfigService {

    @Value("${ledger.rocksdb.path:/tmp/ledger/rocksdb}")
    private String rocksdbPath;

    @Value("${kafka.bootstrap.servers:localhost:9092}")
    private String kafkaBootstrapServers;

    @Value("${outbox.poll.interval.secs:10}")
    private int outboxPollIntervalSecs;

    @Value("${outbox.batch.size:100}")
    private int outboxBatchSize;

    @Value("${ledger.raft.data.path:/tmp/ledger/raft}")
    private String raftDataPath;

    @Value("${ledger.raft.server-port:28080}")
    private int raftServerPort;

    @Override
    public String get(String key, String def) {
        // For the centralized config keys we own, read from @Value fields.
        // Any other key falls through to env var as fallback.
        return switch (key) {
            case "LEDGER_ROCKSDB_PATH" -> rocksdbPath;
            case "KAFKA_BOOTSTRAP_SERVERS" -> kafkaBootstrapServers;
            case "LEDGER_RAFT_DATA_PATH" -> raftDataPath;
            case "RAFT_SERVER_PORT" -> String.valueOf(raftServerPort);
            default -> System.getenv(key);
        };
    }

    @Override
    public int getInt(String key, int def) {
        if (key.equals("OUTBOX_POLL_INTERVAL_SECS")) return outboxPollIntervalSecs;
        if (key.equals("OUTBOX_BATCH_SIZE")) return outboxBatchSize;
        if (key.equals("RAFT_SERVER_PORT")) return raftServerPort;
        return def;
    }

    @Override
    public long getLong(String key, long def) {
        return def;
    }

    @Override
    public boolean getBool(String key, boolean def) {
        return def;
    }
}

package com.tomma8.ledger.rest.config;

import com.tomma8.ledger.config.ConfigService;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * Spring-backed ConfigService implementation.
 *
 * Resolves configuration through Spring {@link Environment}, which merges
 * command-line args, environment variables, and application.yml in standard
 * Spring property order.
 *
 * Legacy env keys (NODE_ID, PEER_NODES, LEDGER_ROCKSDB_PATH, …) map to
 * canonical {@code ledger.*} / {@code outbox.*} property paths so callers can
 * use either form. Property paths are also accepted directly
 * (e.g. {@code ledger.rocksdb.path}).
 */
@Service
public class SpringConfigService implements ConfigService {

  private static final Map<String, String> LEGACY_ENV_TO_PROPERTY =
      Map.ofEntries(
          Map.entry("NODE_ID", "ledger.node.id"),
          Map.entry("PEER_NODES", "ledger.raft.peers"),
          Map.entry("CONSENSUS_ENGINE", "ledger.raft.engine"),
          Map.entry("LEDGER_RAFT_DATA_PATH", "ledger.raft.data-path"),
          Map.entry("RAFT_SERVER_PORT", "ledger.raft.server-port"),
          Map.entry("LEDGER_ROCKSDB_PATH", "ledger.rocksdb.path"),
          Map.entry("LEDGER_ROCKSDB_CACHE_MB", "ledger.rocksdb.cache-mb"),
          Map.entry("LEDGER_ROCKSDB_WRITE_BUFFER_MB", "ledger.rocksdb.write-buffer-mb"),
          Map.entry("LEDGER_ROCKSDB_FSYNC", "ledger.rocksdb.fsync"),
          Map.entry("LEDGER_RAFT_LOG_FSYNC", "ledger.raft.log-fsync"),
          Map.entry("LEDGER_ADVERTISE_URL", "ledger.advertise-url"),
          Map.entry("LEDGER_KAFKA_REQUIRED", "ledger.kafka.required"),
          Map.entry("KAFKA_BOOTSTRAP_SERVERS", "ledger.kafka.bootstrap-servers"),
          Map.entry("LEDGER_COMMAND_QUEUE_ENABLED", "ledger.command-queue.enabled"),
          Map.entry("LEDGER_COMMAND_QUEUE_MAX_SIZE", "ledger.command-queue.max-size"),
          Map.entry("LEDGER_COMMAND_QUEUE_BATCH_SIZE", "ledger.command-queue.batch-size"),
          Map.entry("LEDGER_COMMAND_QUEUE_BATCH_WAIT_MS", "ledger.command-queue.batch-wait-ms"),
          Map.entry("LEDGER_IDEMPOTENCY_TTL", "ledger.idempotency.ttl"),
          Map.entry("LEDGER_MAX_INFLIGHT_POSTINGS", "ledger.posting.max-inflight"),
          Map.entry("LEDGER_INFLIGHT_ACQUIRE_MS", "ledger.posting.inflight-acquire-ms"),
          Map.entry("OUTBOX_POLL_INTERVAL_SECS", "outbox.poll-interval-secs"),
          Map.entry("OUTBOX_BATCH_SIZE", "outbox.batch-size"),
          Map.entry("SERVER_PORT", "server.port"),
          Map.entry("HOSTNAME", "ledger.node.hostname"),
          Map.entry("SNOWFLAKE_WORKER_ID", "ledger.snowflake.worker-id"),
          Map.entry("SPRING_DATASOURCE_URL", "spring.datasource.url"),
          Map.entry("SPRING_DATASOURCE_USERNAME", "spring.datasource.username"),
          Map.entry("SPRING_DATASOURCE_PASSWORD", "spring.datasource.password"));

  private final Environment env;

  public SpringConfigService(Environment env) {
    this.env = env;
  }

  @Override
  public String get(String key, String def) {
    String propertyKey = resolvePropertyKey(key);
    if (propertyKey != null) {
      return env.getProperty(propertyKey, def);
    }
    String fromEnv = System.getenv(key);
    return fromEnv != null ? fromEnv : def;
  }

  @Override
  public int getInt(String key, int def) {
    String propertyKey = resolvePropertyKey(key);
    if (propertyKey != null) {
      return env.getProperty(propertyKey, Integer.class, def);
    }
    String raw = System.getenv(key);
    if (raw != null && !raw.isBlank()) {
      try {
        return Integer.parseInt(raw.trim());
      } catch (NumberFormatException ignored) {
        return def;
      }
    }
    return def;
  }

  @Override
  public long getLong(String key, long def) {
    String propertyKey = resolvePropertyKey(key);
    if (propertyKey != null) {
      return env.getProperty(propertyKey, Long.class, def);
    }
    String raw = System.getenv(key);
    if (raw != null && !raw.isBlank()) {
      try {
        return Long.parseLong(raw.trim());
      } catch (NumberFormatException ignored) {
        return def;
      }
    }
    return def;
  }

  @Override
  public boolean getBool(String key, boolean def) {
    String propertyKey = resolvePropertyKey(key);
    if (propertyKey != null) {
      return env.getProperty(propertyKey, Boolean.class, def);
    }
    String raw = System.getenv(key);
    if (raw != null && !raw.isBlank()) {
      return Boolean.parseBoolean(raw.trim());
    }
    return def;
  }

  private String resolvePropertyKey(String key) {
    if (env.containsProperty(key)) {
      return key;
    }
    return LEGACY_ENV_TO_PROPERTY.get(key);
  }
}

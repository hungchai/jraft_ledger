package com.tomma8.ledger.rest.config;

import com.tomma8.ledger.config.ConfigService;
import com.tomma8.ledger.rest.config.properties.DataSourceConnectionProperties;
import com.tomma8.ledger.rest.config.properties.LedgerProperties;
import com.tomma8.ledger.rest.config.properties.OutboxProperties;
import com.tomma8.ledger.rest.config.properties.ServerPortProperties;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.stereotype.Service;

/**
 * Bridges legacy {@link ConfigService} keys to type-safe
 * {@link org.springframework.boot.context.properties.ConfigurationProperties}
 * beans. Application code must inject {@link LedgerProperties} / {@link OutboxProperties}
 * directly — do not call {@code Environment.getProperty} or {@code System.getenv}.
 */
@Service
public class SpringConfigService implements ConfigService {

  private final LedgerProperties ledger;
  private final OutboxProperties outbox;
  private final DataSourceConnectionProperties datasource;
  private final ServerPortProperties server;

  public SpringConfigService(LedgerProperties ledger,
                             OutboxProperties outbox,
                             DataSourceConnectionProperties datasource,
                             ServerPortProperties server) {
    this.ledger = ledger;
    this.outbox = outbox;
    this.datasource = datasource;
    this.server = server;
  }

  @Override
  public String get(String key, String def) {
    return switch (key) {
      case "NODE_ID" -> emptyToDefault(ledger.getNode().getId(), def);
      case "HOSTNAME" -> emptyToDefault(ledger.getNode().getHostname(), def);
      case "LEDGER_ADVERTISE_URL" -> emptyToDefault(ledger.getAdvertiseUrl(), def);
      case "PEER_NODES" -> emptyToDefault(ledger.getRaft().getPeers(), def);
      case "CONSENSUS_ENGINE" -> emptyToDefault(ledger.getRaft().getEngine(), def);
      case "LEDGER_RAFT_DATA_PATH" -> ledger.getRaft().getDataPath();
      case "LEDGER_ROCKSDB_PATH" -> ledger.getRocksdb().getPath();
      case "KAFKA_BOOTSTRAP_SERVERS" -> ledger.getKafka().getBootstrapServers();
      case "LEDGER_IDEMPOTENCY_TTL" -> DurationStyle.SIMPLE.print(ledger.getIdempotency().getTtl());
      case "SNOWFLAKE_WORKER_ID" -> emptyToDefault(ledger.getSnowflake().getWorkerId(), def);
      case "SPRING_DATASOURCE_URL" -> emptyToDefault(datasource.getUrl(), def);
      case "SPRING_DATASOURCE_USERNAME" -> emptyToDefault(datasource.getUsername(), def);
      case "SPRING_DATASOURCE_PASSWORD" -> emptyToDefault(datasource.getPassword(), def);
      case "ledger.node.id" -> emptyToDefault(ledger.getNode().getId(), def);
      case "ledger.node.hostname" -> emptyToDefault(ledger.getNode().getHostname(), def);
      case "ledger.advertise-url" -> emptyToDefault(ledger.getAdvertiseUrl(), def);
      case "ledger.raft.peers" -> emptyToDefault(ledger.getRaft().getPeers(), def);
      case "ledger.raft.engine" -> ledger.getRaft().getEngine();
      case "ledger.raft.data-path" -> ledger.getRaft().getDataPath();
      case "ledger.raft.server-port" -> String.valueOf(ledger.getRaft().getServerPort());
      case "ledger.rocksdb.path" -> ledger.getRocksdb().getPath();
      case "ledger.kafka.bootstrap-servers" -> ledger.getKafka().getBootstrapServers();
      case "ledger.idempotency.ttl" -> DurationStyle.SIMPLE.print(ledger.getIdempotency().getTtl());
      case "ledger.snowflake.worker-id" -> emptyToDefault(ledger.getSnowflake().getWorkerId(), def);
      case "spring.datasource.url" -> emptyToDefault(datasource.getUrl(), def);
      case "spring.datasource.username" -> emptyToDefault(datasource.getUsername(), def);
      case "spring.datasource.password" -> emptyToDefault(datasource.getPassword(), def);
      case "server.port" -> String.valueOf(server.getPort());
      default -> def;
    };
  }

  @Override
  public int getInt(String key, int def) {
    return switch (key) {
      case "RAFT_SERVER_PORT" -> ledger.getRaft().getServerPort();
      case "LEDGER_ROCKSDB_CACHE_MB" -> ledger.getRocksdb().getCacheMb();
      case "LEDGER_ROCKSDB_WRITE_BUFFER_MB" -> ledger.getRocksdb().getWriteBufferMb();
      case "LEDGER_COMMAND_QUEUE_MAX_SIZE" -> ledger.getCommandQueue().getMaxSize();
      case "LEDGER_COMMAND_QUEUE_BATCH_SIZE" -> ledger.getCommandQueue().getBatchSize();
      case "OUTBOX_BATCH_SIZE" -> outbox.getBatchSize();
      case "OUTBOX_ASYNC_QUEUE_CAPACITY" -> outbox.getAsync().getQueueCapacity();
      case "OUTBOX_ASYNC_DRAIN_THREADS" -> outbox.getAsync().getDrainThreads();
      case "LEDGER_MAX_INFLIGHT_POSTINGS" -> ledger.getPosting().getMaxInflight();
      case "LEDGER_POSTING_TRACE_SAMPLE" -> ledger.getPosting().getTraceSampleN();
      case "OUTBOX_POLL_INTERVAL_SECS" -> outbox.getPollIntervalSecs();
      case "SERVER_PORT" -> server.getPort();
      case "ledger.raft.server-port" -> ledger.getRaft().getServerPort();
      case "ledger.rocksdb.cache-mb" -> ledger.getRocksdb().getCacheMb();
      case "ledger.rocksdb.write-buffer-mb" -> ledger.getRocksdb().getWriteBufferMb();
      case "ledger.command-queue.max-size" -> ledger.getCommandQueue().getMaxSize();
      case "ledger.command-queue.batch-size" -> ledger.getCommandQueue().getBatchSize();
      case "ledger.posting.max-inflight" -> ledger.getPosting().getMaxInflight();
      case "ledger.posting.trace-sample-n" -> ledger.getPosting().getTraceSampleN();
      case "outbox.batch-size" -> outbox.getBatchSize();
      case "outbox.poll-interval-secs" -> outbox.getPollIntervalSecs();
      case "outbox.async.queue-capacity" -> outbox.getAsync().getQueueCapacity();
      case "outbox.async.drain-threads" -> outbox.getAsync().getDrainThreads();
      case "server.port" -> server.getPort();
      default -> def;
    };
  }

  @Override
  public long getLong(String key, long def) {
    return switch (key) {
      case "LEDGER_COMMAND_QUEUE_BATCH_WAIT_MS" -> ledger.getCommandQueue().getBatchWaitMs();
      case "LEDGER_INFLIGHT_ACQUIRE_MS" -> ledger.getPosting().getInflightAcquireMs();
      case "ledger.command-queue.batch-wait-ms" -> ledger.getCommandQueue().getBatchWaitMs();
      case "ledger.posting.inflight-acquire-ms" -> ledger.getPosting().getInflightAcquireMs();
      default -> def;
    };
  }

  @Override
  public boolean getBool(String key, boolean def) {
    return switch (key) {
      case "LEDGER_ROCKSDB_FSYNC" -> ledger.getRocksdb().isFsync();
      case "LEDGER_RAFT_LOG_FSYNC" -> ledger.getRaft().isLogFsync();
      case "LEDGER_KAFKA_REQUIRED" -> ledger.getKafka().isRequired();
      case "LEDGER_COMMAND_QUEUE_ENABLED" -> ledger.getCommandQueue().isEnabled();
      case "ledger.rocksdb.fsync" -> ledger.getRocksdb().isFsync();
      case "ledger.raft.log-fsync" -> ledger.getRaft().isLogFsync();
      case "ledger.kafka.required" -> ledger.getKafka().isRequired();
      case "ledger.command-queue.enabled" -> ledger.getCommandQueue().isEnabled();
      default -> def;
    };
  }

  private static String emptyToDefault(String value, String def) {
    if (value == null || value.isBlank()) {
      return def;
    }
    return value;
  }
}

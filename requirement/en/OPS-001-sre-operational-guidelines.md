# OPS-001 SRE / DevOps Operational Guidelines

**Version**: v0.1
**Date**: 2026-05-22
**Status**: Draft for Review
**Audience**: SRE, Platform Engineers, On-call Engineers

---

## Table of Contents

1. [RocksDB Compaction](#1-rocksdb-compaction)
2. [Raft Node Out-of-Sync Recovery](#2-raft-node-out-of-sync-recovery)
3. [MySQL View Layer Out-of-Sync Recovery](#3-mysql-view-layer-out-of-sync-recovery)
4. [Prometheus / Grafana Monitoring](#4-prometheus--grafana-monitoring)

---

## 1. RocksDB Compaction

### 1.1 Why Compaction Is Needed

RocksDB uses a Log-Structured Merge-Tree (LSM) architecture. Writes are append-only to WAL and memtables, which are periodically flushed to immutable SST files on disk. Over time:

- **SST file count grows** → read amplification increases (queries must scan more files)
- **Disk space balloons** → deleted/overwritten keys still exist in older SST levels until compacted
- **Read latency degrades** → especially for cold accounts loaded on-demand from RocksDB

At 20M JournalLines/day (~10GB RocksDB growth), compaction is essential to keep read latency within NFR targets.

### 1.2 Current State

`RocksDBManager` (`ledger-core/.../rocksdb/RocksDBManager.java`) does **not** expose a compaction API. Automatic background compaction is handled by RocksDB's internal threads, but:

- Default compaction may not keep pace with 10k TPS peak writes
- Manual full compaction ensures predictable performance for EOD reconciliation queries

### 1.3 Recommended Procedure: Daily Full Compaction

**Step 1 — Add compaction API to RocksDBManager**

```java
// In RocksDBManager.java
public void compactAll() throws Exception {
    log.info("Starting full RocksDB compaction for {} column families", columnFamilyHandles.size());
    for (ColumnFamilyHandle handle : columnFamilyHandles.values()) {
        rocksDB.compactRange(handle);
    }
    log.info("RocksDB compaction completed");
}
```

**Step 2 — Schedule off-peak compaction via Spring**

```java
@Component
public class RocksDBCompactionJob {

    private final RocksDBManager rocksDBManager;

    public RocksDBCompactionJob(RocksDBManager rocksDBManager) {
        this.rocksDBManager = rocksDBManager;
    }

    // Run daily at 03:00 local time (post-EOD, pre-market)
    @Scheduled(cron = "0 0 3 * * *")
    public void compact() {
        try {
            rocksDBManager.compactAll();
        } catch (Exception e) {
            // Alert PagerDuty — compaction failure risks next-day query degradation
            throw new RuntimeException("RocksDB compaction failed", e);
        }
    }
}
```

**Step 3 — Enable scheduling in Spring Boot**

```java
@EnableScheduling
@SpringBootApplication
public class LedgerApplication { ... }
```

### 1.4 Compaction Monitoring

| Metric | Alert Threshold | Meaning |
|---|---|---|
| `rocksdb_total_sst_files_size` | > 50 GB per node | Uncompacted data growth |
| `rocksdb_compaction_pending` | > 10 for > 30 min | Compaction cannot keep up with writes |
| `rocksdb_num_running_compactions` | 0 for > 2 hours during peak | Background compaction stalled |
| Compaction job duration | > 60 minutes | May need to shard column families |

### 1.5 Emergency Manual Compaction

If read latency spikes during market hours:

```bash
# Trigger compaction via JMX or actuator endpoint (to be implemented)
curl -X POST http://ledger-node:8080/actuator/compact

# Or restart node with --rocksdb.force-compact-on-start (if flag added)
```

**Caution**: Full compaction is I/O-heavy. During peak hours, it may cause:
- Temporary write latency spikes (≥ 20ms)
- CPU usage increase (compaction threads)

**Mitigation**: Limit compaction threads via `ColumnFamilyOptions.setMaxSubcompactions(2)`.

---

## 2. Raft Node Out-of-Sync Recovery

### 2.1 Detection

Use the `/ledger/cluster/raft-status` endpoint (NFR-17) on all nodes:

```bash
# Poll all nodes
for node in node1 node2 node3; do
  curl -s http://${node}:8080/ledger/cluster/raft-status | jq .
done
```

**Healthy state:**
```json
{
  "nodeId": "node2",
  "isLeader": false,
  "term": 5,
  "lastAppliedIndex": 124857,
  "alivePeers": ["node1:28080", "node3:28080"]
}
```

**Unhealthy signatures:**

| Signature | Severity | Likely Cause |
|---|---|---|
| `lastAppliedIndex` lags leader by > 100 entries for > 30s | WARNING | Slow follower (GC, network) |
| `lastAppliedIndex` lags leader by > 1000 entries for > 60s | CRITICAL | Follower stalled or crashed |
| `alivePeers` count < quorum | CRITICAL | Network partition |
| No node reports `isLeader: true` for > 30s | CRITICAL | Split-brain or total leader loss |
| `term` diverges across nodes for > 10s | CRITICAL | Split-brain |

### 2.2 Diagnosis Flowchart

```
Follower lag detected?
        │
        ▼
┌─────────────────────┐
│ lag ≤ 100 entries │  →  Monitor only; Raft will auto-catch-up
└─────────────────────┘
        │ lag > 100
        ▼
┌─────────────────────┐
│  Node responsive?   │  →  Check: curl /actuator/health
│  (HTTP 200?)        │
└─────────────────────┘
        │                    │
      YES                  NO
        │                    │
        ▼                    ▼
┌──────────────┐     ┌─────────────────────┐
│ GC pause?    │     │ Node crashed / hung │
│ CPU > 90%?   │     │                     │
└──────────────┘     └─────────────────────┘
        │                    │
      YES                  │
        │                  ▼
        ▼          ┌─────────────────────┐
┌──────────────┐   │  Restart node       │
│ Wait 60s;    │   │  → auto snapshot    │
│ if no catch- │   │    restore from     │
│ up, proceed  │   │    leader           │
└──────────────┘   └─────────────────────┘
        │
      NO catch-up
        ▼
┌─────────────────────┐
│ Trigger manual      │
│ snapshot install    │
│ (Section 2.3)       │
└─────────────────────┘
```

### 2.3 Recovery Procedures

#### Scenario A: Slow Follower (Small Lag, Node Healthy)

**No action required.** SOFAJRaft automatically replicates missing log entries to the follower. Monitor `lastAppliedIndex` until it converges.

**If lag persists > 5 minutes:**

```bash
# 1. Check node logs for repeated errors
kubectl logs ledger-node-2 --tail=200 | grep -i "raft\|error\|warn"

# 2. Check network latency between leader and follower
ping <leader-ip>

# 3. If GC pauses are the cause, verify ZGC is active
jcmd <pid> VM.flags | grep UseZGC
```

#### Scenario B: Stalled Follower (Large Lag, Node Responsive)

The follower's State Machine is far behind. Forcing it to catch up via log replay may take too long. Install a snapshot from the leader instead.

```bash
# 1. On the stalled follower, trigger snapshot installation via SOFAJRaft CLI
# SOFAJRaft provides a CLI tool (raft-kv) for administrative operations

# 2. If CLI is not available, restart the follower node:
#    - Stop the node
#    - Delete local Raft log and snapshot directories
#    - Start the node — it will request a snapshot from the leader automatically

kubectl exec ledger-node-2 -- sh -c '
  # Backup before deletion
  mv /data/ledger/raft/log /data/ledger/raft/log.bak.$(date +%s)
  mv /data/ledger/raft/snapshot /data/ledger/raft/snapshot.bak.$(date +%s)
'

# Restart pod / service
kubectl rollout restart deployment/ledger-node-2
```

**What happens on restart:**
1. Node starts as Follower with empty log
2. Contacts Leader, requests latest snapshot
3. Leader sends snapshot via `onSnapshotSave()` → `onSnapshotLoad()`
4. Follower applies snapshot, then catches up remaining logs
5. Ready to serve within RTO ≤ 1 minute (NFR-4)

#### Scenario C: Corrupted Follower (Node Crashes on Apply)

If the follower crashes repeatedly during `onApply()` or `onSnapshotLoad()`, its local RocksDB may be corrupted.

```bash
# 1. Stop the node
kubectl scale deployment ledger-node-2 --replicas=0

# 2. Delete ALL local data (RocksDB + Raft metadata + snapshots)
kubectl exec ledger-node-2 -- rm -rf /data/ledger/rocksdb/*
kubectl exec ledger-node-2 -- rm -rf /data/ledger/raft/*

# 3. Start the node — full state transfer from leader
kubectl scale deployment ledger-node-2 --replicas=1
```

**Caution**: Do NOT delete data on more than one node at a time. If 2 of 3 nodes lose data simultaneously, the cluster loses quorum.

#### Scenario D: Split-Brain (Term Divergence)

```bash
# 1. Identify the true leader (the one with the highest term AND majority alivePeers)
for node in node1 node2 node3; do
  curl -s http://${node}:8080/ledger/cluster/raft-status | jq '{nodeId, isLeader, term, alivePeers}'
done
```

**Resolution:**
- If one node has `isLeader: true` and `alivePeers` contains the majority → that is the true leader
- Nodes with lower terms will automatically step down and rejoin
- If two nodes both claim leadership (rare with proper election timeout), **manually step down** the false leader:

```bash
# Call SOFAJRaft Node.resetElectionTimeout() or restart the false leader
# This forces it to become a follower
kubectl rollout restart deployment/ledger-node-false-leader
```

**Never** force-promote a follower to leader manually unless you are absolutely certain it has the latest data.

### 2.4 Preventive Measures

| Measure | Implementation |
|---|---|
| Monitor replication lag | NFR-17 alerts: `RaftFollowerLagHigh`, `RaftFollowerLagCritical` |
| ZGC mandatory | NFR-13: `-XX:+UseZGC` prevents long GC pauses that stall replication |
| Network redundancy | Multi-AZ deployment with redundant links between nodes |
| Snapshot interval | `nodeOptions.setSnapshotIntervalSecs(3600)` — hourly snapshots ensure followers can catch up quickly |
| Disk I/O | Separate SSD for Raft log and RocksDB data; avoid sharing with OS |

---

## 3. MySQL View Layer Out-of-Sync Recovery

### 3.1 Detection

The MySQL View Layer is eventually consistent. Normal lag is ≤ 1 second (NFR-5). Detect drift with:

**Method A — Compare lastAppliedIndex**

```bash
# Leader's last applied index
LEADER_INDEX=$(curl -s http://node1:8080/ledger/cluster/raft-status | jq '.lastAppliedIndex')

# MySQL max journal sequence (proxy for how many journals are projected)
MYSQL_COUNT=$(mysql -h ledger-mysql -u ledger -p ledger123 -N -e \
  "SELECT COUNT(*) FROM journal WHERE created_at > DATE_SUB(NOW(), INTERVAL 5 MINUTE)")

# If leader has written 1000 journals in 5 min but MySQL shows < 900, lag exists
```

**Method B — Kafka consumer lag monitoring**

```bash
# Check Kafka consumer group lag for projection consumer
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group ledger-projection --describe
```

| Lag | Meaning | Action |
|---|---|---|
| `CURRENT-OFFSET == LOG-END-OFFSET` | Consumer is caught up | None |
| `LOG-END-OFFSET - CURRENT-OFFSET` > 1000 for > 60s | Consumer lagging | Investigate (Section 3.3) |
| Consumer not in group | Consumer crashed or disconnected | Restart consumer |

**Method C — L1 Reconciliation mismatch**

EOD reconciliation (F-007) compares:
- Sum of all JournalLines in RocksDB (source of truth)
- Sum of all JournalLines in MySQL View Layer

If mismatch > 0 → MySQL is out of sync.

### 3.2 Root Cause Analysis

| Symptom | Likely Cause | Evidence |
|---|---|---|
| All MySQL tables lag uniformly | ProjectionConsumer crashed or stopped | Kafka consumer group missing; no consumer logs |
| Only `journal_line` lagging | ShardingSphere routing error or shard table missing | MySQL error log: "Table doesn't exist" |
| `account_balance` stale but `journal` fresh | `accountBalanceMapper.upsertBalance()` failing | Consumer logs: "Failed to upsert balance" |
| Intermittent missing rows | Kafka consumer auto-commit offset before DB commit | Consumer config: `enable.auto.commit=true` (should be `false` with manual ack) |
| Duplicate rows in MySQL | ProjectionConsumer processed same event twice | Idempotency key not checked before INSERT |

### 3.3 Recovery Procedures

#### Scenario A: Consumer Lag (Consumer Alive, Just Slow)

```bash
# 1. Check consumer CPU and heap
jcmd <consumer-pid> GC.heap_info
jcmd <consumer-pid> Thread.print | grep -c "kafka"

# 2. If CPU is normal, check MySQL write bottleneck
mysql -e "SHOW PROCESSLIST;"  # Look for long-running INSERTs

# 3. Scale out consumers (if partitioned topic)
# Currently ledger.balance.change.v1 has 1 partition — scaling consumers requires adding partitions
kafka-topics.sh --bootstrap-server localhost:9092 \
  --alter --topic ledger.balance.change.v1 --partitions 4

# 4. Restart consumer to clear any stuck state
kubectl rollout restart deployment/ledger-projection
```

#### Scenario B: Consumer Crashed (No Consumer in Group)

```bash
# 1. Restart the projection consumer
kubectl rollout restart deployment/ledger-projection

# 2. Verify it rejoins the consumer group
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group ledger-projection --describe

# 3. Monitor lag until caught up
watch -n 5 'kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group ledger-projection --describe'
```

**Important**: The consumer is idempotent (INSERT with catch-exception). Restarting is safe — duplicate events are ignored.

#### Scenario C: Partial Data Corruption (Some Rows Missing in MySQL)

If only specific accounts or time ranges are missing:

```bash
# 1. Identify the affected account_id and time range
mysql -e "SELECT account_id, MIN(created_at), MAX(created_at)
  FROM journal_line WHERE journal_id IN (SELECT journal_id FROM journal
  WHERE created_at > '2026-05-20 00:00:00') GROUP BY account_id;"

# 2. Replay from RocksDB state machine
# The LedgerStateMachine journalStore holds all journals in memory (and RocksDB)
# Export the affected journals and re-insert them manually
```

**Programmatic replay (to be implemented as admin tool):**

```java
// Admin endpoint: replay journals from RocksDB to MySQL
@PostMapping("/admin/replay-journals")
public ResponseEntity<?> replayJournals(
        @RequestParam String startJournalId,
        @RequestParam String endJournalId) {
    // 1. Read journals from journalStore (in-memory or RocksDB)
    // 2. For each journal, call ProjectionConsumer logic directly
    // 3. Insert into MySQL with idempotency check
}
```

#### Scenario D: Full MySQL Rebuild (Complete View Layer Corruption)

If MySQL data is fundamentally corrupted (e.g., wrong balances, missing journals), rebuild from scratch:

```bash
# Step 1 — Stop the projection consumer
kubectl scale deployment ledger-projection --replicas=0

# Step 2 — Truncate MySQL view layer tables
mysql -e "
  SET FOREIGN_KEY_CHECKS = 0;
  TRUNCATE TABLE journal;
  TRUNCATE TABLE journal_line_0;
  TRUNCATE TABLE journal_line_1;
  TRUNCATE TABLE journal_line_2;
  TRUNCATE TABLE journal_line_3;
  TRUNCATE TABLE account_balance;
  TRUNCATE TABLE account;
  SET FOREIGN_KEY_CHECKS = 1;
"

# Step 3 — Reset Kafka consumer offset to beginning
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group ledger-projection --topic ledger.balance.change.v1 \
  --reset-offsets --to-earliest --execute

kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group ledger-projection --topic ledger.account.v1 \
  --reset-offsets --to-earliest --execute

# Step 4 — Restart projection consumer
kubectl scale deployment ledger-projection --replicas=1

# Step 5 — Monitor until caught up
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group ledger-projection --describe
```

**Rebuild time estimate:**
- 1M journals ≈ 15–30 minutes (depends on MySQL write throughput)
- 5M journals ≈ 1–2 hours
- During rebuild, journal queries return stale data (acceptable for EOD; not acceptable during market hours)

**Alternative: Rebuild from State Machine snapshot instead of Kafka replay**

If Kafka retention is shorter than the corruption window:

```bash
# 1. Export snapshot from RocksDB on Leader
# The snapshot contains all balances, journals, and accounts

# 2. Parse snapshot JSON and bulk-insert into MySQL
# This is faster than event-by-event replay but requires a custom script
```

### 3.4 Preventive Measures

| Measure | Implementation |
|---|---|
| Consumer lag alert | Prometheus: `kafka_consumer_lag` > 1000 for > 60s → PagerDuty |
| Consumer health check | `/actuator/health` includes Kafka consumer indicator |
| Idempotent INSERTs | `INSERT ... ON DUPLICATE KEY UPDATE` or catch-exception pattern (already implemented) |
| MySQL replication (optional) | Deploy MySQL read replica for reporting queries; keep primary for projection writes |
| Kafka retention | `retention.ms = 7 days` minimum; ensures enough history for rebuilds |
| EOD reconciliation | L1 reconciliation (RocksDB vs MySQL) runs every night; catches drift within T+0 |

---

## 4. Prometheus / Grafana Monitoring

### 4.1 Access Endpoints

| Service | URL | Default Account |
|---|---|---|
| Prometheus | http://localhost:9090 | None |
| Grafana | http://localhost:3000 | admin / admin123 |
| Node 1 metrics | http://localhost:8081/actuator/prometheus | None |
| Node 2 metrics | http://localhost:8082/actuator/prometheus | None |
| Node 3 metrics | http://localhost:8083/actuator/prometheus | None |
| Projection metrics | http://localhost:8089/actuator/prometheus | None |

### 4.2 Prometheus Targets Status

Check all scrape targets are UP:

```bash
curl -s http://localhost:9090/api/v1/targets | jq '.data.activeTargets[] | {job:.labels.job,health:.health}'
```

Expected output:

```
{"job": "ledger-nodes", "health": "up"}
{"job": "projection", "health": "up"}
{"job": "prometheus", "health": "up"}
```

If target status is `down`, check:

- Docker container is running: `docker ps`
- Actuator endpoint is exposed: `curl http://ledger-node-1:8080/actuator/prometheus`
- Prometheus config loaded correctly: `docker exec ledger-prometheus cat /etc/prometheus/prometheus.yml`

### 4.3 Grafana Dashboard

Default dashboard (`grafana/provisioning/dashboards/ledger-overview.json`) contains the following panels:

| Panel | Metrics | NFR Target |
|---|---|---|
| Posting P95 latency | `ledger_posting_duration_seconds{quantile="0.95"}` | ≤ 3ms |
| Balance Query latency | `ledger_balance_query_duration_seconds{quantile="0.95",queryType="live"}` | ≤ 2ms |
| Raft Leader status | `ledger_raft_is_leader` | 1 (single node = 1) |
| Account Queue depth | `ledger_account_queue_depth` | < 500 |
| GC Pause time | `jvm_gc_pause_seconds_max` | < 5ms |
| Kafka consumer lag | `kafka_consumer_lag` | < 1000 |

Dashboard JSON configuration: `grafana/provisioning/dashboards/ledger-overview.json`.

### 4.4 Common PromQL Queries

```promql
# Posting P95 last 5 minutes
histogram_quantile(0.95, rate(ledger_posting_duration_seconds_bucket[5m]))

# Balance Query P95 (live)
histogram_quantile(0.95, rate(ledger_balance_query_duration_seconds_bucket{queryType="live"}[5m]))

# Max GC pause last 1 minute
max(jvm_gc_pause_seconds_max)

# Raft Leader node
max_by (node_id) (ledger_raft_is_leader)

# Deepest account queues
topk(5, ledger_account_queue_depth)
```

### 4.5 Alert Rules Configuration

Prometheus AlertManager must be configured with the following rules (see `prometheus/alert-rules.yml`):

| Alert Name | Condition | Severity |
|---|---|---|
| PostingP95High | histogram_quantile(0.95, rate(ledger_posting_duration_seconds_bucket[5m])) > 0.003 | WARNING |
| PostingP99Critical | histogram_quantile(0.99, rate(ledger_posting_duration_seconds_bucket[5m])) > 0.05 | CRITICAL |
| GCPauseTooLong | jvm_gc_pause_seconds_max > 0.005 | CRITICAL |
| QueueBacklogHigh | ledger_account_queue_depth > 500 | WARNING |
| RaftFollowerLagHigh | max(ledger_raft_last_applied_index) - ledger_raft_last_applied_index > 100 | WARNING |

---

## Appendix A: Emergency Contact & Runbook References

| Situation | First Response | Escalation |
|---|---|---|
| RocksDB disk full | Trigger compaction; expand EBS | Platform Engineering |
| Raft quorum lost | Do NOT restart multiple nodes; identify partition | SRE Lead |
| All MySQL data corrupted | Initiate full rebuild from Kafka | DBA + SRE Lead |
| Posting P99 > 50ms | Check GC pauses, queue backlog, Raft term | On-call SRE |

## Appendix B: Quick Command Reference

```bash
# RocksDB SST file count and size
ls -lh /data/ledger/rocksdb/*.sst | wc -l

# Raft node status (all nodes)
for n in node1 node2 node3; do curl -s http://${n}:8080/ledger/cluster/raft-status | jq -c '{nodeId,isLeader,term,lastAppliedIndex}'; done

# Kafka consumer lag
kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group ledger-projection --describe

# MySQL journal count (last hour)
mysql -e "SELECT COUNT(*) FROM journal WHERE created_at > DATE_SUB(NOW(), INTERVAL 1 HOUR);"

# ZGC pause check
jcmd <pid> GC.run_finalization  # or check GC log for pauses > 5ms
```

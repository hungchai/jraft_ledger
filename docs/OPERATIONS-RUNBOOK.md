# Ledger Platform — Operations Runbook

**Version:** 1.0  
**Date:** 2026-05-25  
**Scope:** RocksDB data management, Raft node sync, backup/recovery procedures

---

## 1. RocksDB Daily Operations

### 1.1 Data Layout

| Column Family | Content | Growth Rate (at 10K postings/hr) |
|---|---|---|
| `journal` | Journal metadata (JSON) | ~5 MB/day |
| `journal_line` | Per-line balance snapshots (JSON) | ~10 MB/day |
| `balance` | Current balance per `(accountId, balanceType, position, currency)` | ~5 MB/day |
| `idempotency` | `requestId` → result cache | ~2 MB/day |
| `account_meta` | Account metadata | ~1 MB/day |
| `balance_type` | Balance type configs | Negligible |
| `sm_snapshot` | Full state machine snapshot (`snapshot:latest`) | ~1 per snapshot, overwritten |
| `outbox` | BalanceChangeEvent queue for Kafka | ~10 MB/day (drained by async publisher) |

**Total raw growth:** ~33 MB/day uncompressed. With LZ4/Snappy: ~12–15 MB/day.  
**90 days hot data:** ~1–1.5 GB. **1 year:** ~5 GB.

### 1.2 Daily Health Checks

Run every morning (cron or operator):

```bash
# 1. SST file count — should be stable day-over-day. Sudden spike = compaction issue.
ls -l /data/ledger/rocksdb/*.sst | wc -l

# 2. DB size on disk
du -sh /data/ledger/rocksdb/

# 3. WAL file count — should stay low (0–2). Growing = pending flushes.
ls -l /data/ledger/rocksdb/*.log | wc -l

# 4. Check for corruption markers
grep -r "Corruption\|checksum mismatch\|RocksDB" /var/log/ledger/ledger.log | tail -20

# 5. Snapshot age — via Prometheus metric or log grep
# Alert if > 1 hour since last snapshot
grep "Snapshot saved\|takeSnapshot" /var/log/ledger/ledger.log | tail -5
```

### 1.3 Weekly Compaction Maintenance

RocksDB uses leveled compaction by default. For this write-heavy workload:

```bash
# Manual compaction during low-traffic window (Sunday 03:00 UTC):
# Trigger via JMX or admin API — compact the entire DB
curl -X POST http://<leader-node>:8080/ledger/admin/rocksdb/compact

# Equivalent manual approach (if no admin endpoint):
# 1. Stop writes gracefully
# 2. Let pending compactions finish (monitor SST count)
# 3. Optionally: trigger CompactRange via RocksDB API
```

**Signs of compaction debt:**
- SST file count > 500 (level 0) — read amplification kills query P95
- `rocksdb.num.files.in.level0` metric climbing
- Balance query P95 exceeding 2 ms target
- Disk space growing faster than expected (stale data in overlapping SSTs)

### 1.4 Periodic Data Archival

This codebase writes all journal + balance data to MySQL via the Projection sidecar (`ProjectionConsumer`). RocksDB is the **hot** operational store only.

**Archival procedure (monthly):**

1. **Verify MySQL projection is current:**
   ```sql
   SELECT MAX(raft_log_index) FROM journal_projection;
   -- Compare to Leader's raftLogIndex
   ```

2. **Take final snapshot of active data:**
   ```bash
   curl -X POST http://<leader-node>:8080/ledger/admin/snapshot
   ```

3. **Archive old RocksDB SST files (older than 90 days):**
   ```bash
   # RocksDB doesn't support time-based TTL natively.
   # Instead: snapshot → restore with filtered data → replace DB directory.
   # Or: rely on compaction deleting old tombstones.
   ```

**Recommendation:** Implement TTL column family for `idempotency` and `outbox` — these are the fastest-growing CFs and don't need permanent retention. 90-day TTL for idempotency, 7-day for outbox.

### 1.5 Disk Space Monitoring

| Threshold | Action |
|---|---|
| 70% disk | Warning alert |
| 80% disk | Trigger manual compaction, verify no stalled flushes |
| 90% disk | Critical — stop accepting new postings, force archival |
| 95% disk | Emergency — RocksDB may go read-only |

**Metric:** `node_filesystem_avail_bytes{mountpoint="/data"}`

---

## 2. Raft Node Sync

### 2.1 Normal Sync Flow

```
Leader ──AppendEntries RPC──→ Follower-1
   │                          Follower-2
   │                          Learner (as-of query)
   │
   └── Every append:
       1. Leader writes to local RocksDB WAL
       2. Replicates log entry to quorum (N/2+1)
       3. On quorum ack → commit → StateMachine.apply()
       4. Followers apply after commit index advances
```

**Sync check (manual):**

```bash
# On each node, query lastAppliedIndex:
curl -s http://<node>:8080/ledger/admin/raft/status | jq '.lastAppliedIndex'

# All nodes should be within ~100 entries of each other.
# Learner may lag more (up to a few seconds).
```

### 2.2 Detecting Out-of-Sync

**Symptoms:**
- `lastAppliedIndex` on follower diverges from leader by >1000 entries and growing
- Follower repeatedly sends `VoteRequest` (split-brain indicator)
- Raft log on follower has uncommitted entries with different terms
- `onSnapshotLoad` fails on follower after leader transfer

**Prometheus alert:**
```
ledger_raft_log_lag{node="follower-X"} > 1000
```

**Root causes:**
1. **Network partition** — follower isolated, rejoins with stale log
2. **Slow disk** — follower can't keep up with apply rate (10K postings/hr = ~3/sec, trivial. If posting spikes to 1000/sec, disk IO matters)
3. **Snapshot corruption** — follower snapshot file is corrupt, `onSnapshotLoad` returns false, follower can't catch up
4. **Term mismatch** — follower has entries from an old term that the current leader doesn't have (resolved by log truncation)

### 2.3 Recovery Procedure: Follower Out of Sync

**Case A: Follower slightly behind (lag < 5000 entries)**

Do nothing. Leader's `AppendEntries` will catch it up. Monitor lag decreasing.

**Case B: Follower significantly behind (lag > 5000 entries, or snapshot corruption)**

```
Step 1: Identify the stale node
  curl http://<suspected-node>:8080/ledger/admin/raft/status

Step 2: Stop the ledger process on that node
  systemctl stop ledger

Step 3: Remove stale data directory
  rm -rf /data/ledger/rocksdb/
  rm -rf /data/ledger/raft-log/

Step 4: Restart the node
  systemctl start ledger

Step 5: The node starts as follower with empty state.
  Leader detects this and triggers InstallSnapshot RPC.
  Leader sends full state_machine_snapshot file.
  Follower calls onSnapshotLoad() → restoreFromBytes().
  Follower then replays Raft log from snapshot's lastAppliedIndex forward.
```

**Case C: Leader corrupted**

```
Step 1: Identify — leader's own snapshot fails to load, or apply() panics
Step 2: Step down the leader:
  curl -X POST http://<leader>:8080/ledger/admin/raft/step-down
Step 3: New leader elected. Old leader becomes follower.
Step 4: Treat old leader as Case B — wipe and re-sync.
Step 5: Verify new leader's lastAppliedIndex matches journal projection.
```

### 2.4 Learner Node Recovery

Learners (used for as-of balance queries) don't participate in quorum. Recovery is simpler:

```bash
systemctl stop ledger-learner
rm -rf /data/ledger-learner/rocksdb/
systemctl start ledger-learner
# Learner catches up via AppendEntries from leader (read-only replication).
```

No snapshot transfer needed — learner can be wiped and will catch up from log.

---

## 3. Backup Policy

### 3.1 What to Back Up

| Data | Location | Size | Criticality |
|---|---|---|---|
| RocksDB data | `/data/ledger/rocksdb/` | ~1–5 GB | **High** — operational state |
| Raft log | `/data/ledger/raft-log/` | ~100 MB | **Medium** — recoverable from snapshot + peers |
| Config files | `/etc/ledger/` | <1 MB | **High** — cluster topology, DB credentials |
| MySQL projection | `ledger_projection` schema | Variable | **High** — long-term audit trail |

### 3.2 Backup Methods

**Method 1: RocksDB Backup (preferred — consistent)**

Use RocksDB's `BackupEngine` for point-in-time consistent backup:

```java
// Create a backup (can run on any node, prefer follower to avoid leader load)
try (BackupEngine backupEngine = BackupEngine.open(Env.getDefault(),
        new BackupEngineOptions("/backup/ledger/"))) {
    backupEngine.createNewBackup(rocksDB, true); // true = flush before backup
}
```

**Method 2: Filesystem Snapshot (if on LVM/ZFS)**

```bash
# LVM snapshot
lvcreate --size 10G --snapshot --name ledger-snap /dev/vg0/ledger-data
mount /dev/vg0/ledger-snap /mnt/ledger-backup
tar -czf /backup/ledger-$(date +%Y%m%d-%H%M).tar.gz -C /mnt/ledger-backup .
umount /mnt/ledger-backup
lvremove -f /dev/vg0/ledger-snap
```

**Method 3: Cold copy (requires stopping the node — use only on follower)**

```bash
systemctl stop ledger
tar -czf /backup/ledger-cold-$(date +%Y%m%d).tar.gz /data/ledger/
systemctl start ledger
```

### 3.3 Backup Schedule

| Frequency | Method | Retention | Storage |
|---|---|---|---|
| **Hourly** | RocksDB incremental backup | 24 hours | Local disk (fast recovery) |
| **Daily** | Full RocksDB backup (on follower) | 7 days | NAS / S3 |
| **Weekly** | Full backup + MySQL dump | 4 weeks | S3 / Glacier |
| **Monthly** | Full backup + verification restore | 12 months | Glacier Deep Archive |

### 3.4 Backup Script (Follower Node)

```bash
#!/bin/bash
# /opt/ledger/scripts/backup.sh
# Run via cron: 0 2 * * * /opt/ledger/scripts/backup.sh daily

BACKUP_TYPE=${1:-hourly}
BACKUP_ROOT=/backup/ledger
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
NODE_ID=$(hostname)
LOG_FILE=/var/log/ledger/backup.log

log() {
    echo "[$(date -Iseconds)] $1" | tee -a "$LOG_FILE"
}

# Check this node is NOT the leader (backup on follower to avoid perf impact)
IS_LEADER=$(curl -s http://localhost:8080/ledger/admin/raft/status | jq -r '.isLeader')
if [ "$IS_LEADER" = "true" ]; then
    log "WARN: This node is leader. Skipping backup to avoid performance impact."
    log "INFO: Backup should run on a follower node."
    exit 0
fi

# Verify RocksDB is healthy
if [ ! -d /data/ledger/rocksdb/CURRENT ]; then
    log "ERROR: RocksDB CURRENT file missing. DB may be corrupted. Aborting."
    exit 1
fi

# Check for corruption markers in logs (last 5 minutes)
if journalctl -u ledger --since "5 min ago" | grep -qi "corruption"; then
    log "ERROR: Corruption detected in recent logs. Aborting backup."
    exit 1
fi

case "$BACKUP_TYPE" in
    hourly)
        # Incremental via BackupEngine (handled by Java admin endpoint)
        curl -s -X POST http://localhost:8080/ledger/admin/rocksdb/backup/create
        log "Hourly incremental backup triggered"
        ;;
    daily)
        # Full backup to NAS
        BACKUP_DIR="$BACKUP_ROOT/daily/$TIMESTAMP"
        mkdir -p "$BACKUP_DIR"

        # Trigger RocksDB checkpoint (consistent snapshot without stopping)
        curl -s -X POST http://localhost:8080/ledger/admin/rocksdb/checkpoint \
            -d "{\"path\": \"$BACKUP_DIR\"}" \
            -H "Content-Type: application/json"

        # Also dump current config
        cp /etc/ledger/*.yml "$BACKUP_DIR/"

        # Compress
        tar -czf "$BACKUP_DIR.tar.gz" -C "$BACKUP_ROOT/daily" "$TIMESTAMP"
        rm -rf "$BACKUP_DIR"

        # Upload to S3
        aws s3 cp "$BACKUP_DIR.tar.gz" "s3://ledger-backups/daily/$TIMESTAMP.tar.gz" \
            --storage-class STANDARD_IA

        # Cleanup old daily backups (> 7 days)
        find "$BACKUP_ROOT/daily" -name "*.tar.gz" -mtime +7 -delete
        log "Daily backup completed: $BACKUP_DIR.tar.gz"
        ;;
    weekly)
        # Full backup + MySQL
        BACKUP_DIR="$BACKUP_ROOT/weekly/$TIMESTAMP"
        mkdir -p "$BACKUP_DIR"

        curl -s -X POST http://localhost:8080/ledger/admin/rocksdb/checkpoint \
            -d "{\"path\": \"$BACKUP_DIR/rocksdb\"}" \
            -H "Content-Type: application/json"

        # MySQL dump (projection schema)
        mysqldump --single-transaction --routines --triggers \
            ledger_projection > "$BACKUP_DIR/projection.sql"

        cp /etc/ledger/*.yml "$BACKUP_DIR/"

        tar -czf "$BACKUP_DIR.tar.gz" -C "$BACKUP_ROOT/weekly" "$TIMESTAMP"
        rm -rf "$BACKUP_DIR"

        aws s3 cp "$BACKUP_DIR.tar.gz" "s3://ledger-backups/weekly/$TIMESTAMP.tar.gz" \
            --storage-class GLACIER

        find "$BACKUP_ROOT/weekly" -name "*.tar.gz" -mtime +28 -delete
        log "Weekly backup completed: $BACKUP_DIR.tar.gz"
        ;;
    *)
        echo "Usage: $0 {hourly|daily|weekly}"
        exit 1
        ;;
esac
```

### 3.5 Backup Verification

Run monthly on a staging/DR node:

```bash
#!/bin/bash
# /opt/ledger/scripts/verify-backup.sh
BACKUP_FILE=$1
RESTORE_DIR=/tmp/ledger-restore-verify

rm -rf "$RESTORE_DIR"
mkdir -p "$RESTORE_DIR"

# 1. Extract
tar -xzf "$BACKUP_FILE" -C "$RESTORE_DIR"

# 2. Start a single-node ledger pointing at the restored data
#    (isolated — no network access to production cluster)
ledger-server --data-dir "$RESTORE_DIR" --mode standalone-verify

# 3. Query key invariants
curl -s http://localhost:8081/ledger/admin/raft/status | jq '.lastAppliedIndex'
curl -s http://localhost:8081/ledger/admin/verify/journal-balance | jq '.consistent'

# 4. If both pass: backup valid. Cleanup.
#    If either fails: investigate, mark backup as suspect.
```

### 3.6 Recovery Time Objective (RTO) & Recovery Point Objective (RPO)

| Scenario | RPO | RTO | Procedure |
|---|---|---|---|
| Single follower disk failure | 0 (leader still up) | <5 min | Wipe and re-sync (Section 2.3 Case B) |
| Leader disk failure | <1 hour (last snapshot) | <2 min | Follower promoted to leader. Old leader rebuilt. |
| Full cluster loss (3 nodes) | <24 hours (last daily backup) | <1 hour | Restore from latest backup → verify → start cluster |
| Region failure | <24 hours | <4 hours | Restore backup to DR region, update DNS |

### 3.7 Snapshot Age Monitoring

Critical for RPO — stale snapshots mean long replay on recovery.

```promql
# Alert if no snapshot in last hour
time() - ledger_raft_last_snapshot_timestamp_seconds > 3600
```

The `LedgerRaftStateMachine.onSnapshotSave()` is triggered by SOFAJRaft's snapshot timer. Default SOFAJRaft triggers snapshot every 3600 seconds OR after 100,000 log entries. Verify in `RaftOptions`:

```java
// Ensure snapshot is frequent enough for your RPO target
nodeOptions.setSnapshotIntervalSecs(1800); // 30 min
```

---

## Appendix: Key Metrics Reference

| Metric | Description | Alert Threshold |
|---|---|---|
| `ledger_raft_log_lag` | Entries behind leader | >5000 for 5 min |
| `ledger_raft_leader_elections_total` | Unexpected elections | >0 in 10 min |
| `rocksdb_num_files_in_level0` | L0 SST count | >100 |
| `rocksdb_compaction_pending` | Pending compactions | >5 |
| `rocksdb_size_bytes` | Total DB size | >80% disk |
| `ledger_raft_last_snapshot_timestamp_seconds` | Age of last snapshot | >3600 |
| `ledger_state_machine_queue_depth` | Account queue depth | >5000 |

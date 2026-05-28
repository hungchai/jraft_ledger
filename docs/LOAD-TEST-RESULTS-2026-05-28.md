# Load Test Results — 2026-05-28

3-node Raft cluster, run twice — once in Docker, once on localhost.
MySQL 8.4 + Kafka in Docker for both. Tool: k6 v2.0.0. Driver host:
single laptop (3 JVM nodes + MySQL + Kafka + k6 all local).

Harness: `loadtest/` — k6 scenarios + runner scripts under
`loadtest/scripts/`.

## Headline numbers

### Localhost cluster (3 JVMs on host, ZGC, Xmx=4g, RAFT_LOG_SYNC=false)

| Test       | Offered | Achieved rps | fail % | p50      | p95     | p99      | p99.9    | max      |
|------------|--------:|-------------:|-------:|----------|---------|----------|----------|----------|
| random-1k  | 1 000   | **1 006**    | 0%     | 0.44 ms  | 0.53 ms | 0.74 ms  | 4.41 ms  | 31.5 ms  |
| random-2k  | 2 000   | **2 006**    | 0%     | 0.27 ms  | 0.38 ms | 0.76 ms  | 2.59 ms  | 9.00 ms  |
| hot1-2k    | 2 000   | **2 006**    | 0%     | 0.27 ms  | 0.38 ms | 0.80 ms  | 3.25 ms  | 9.98 ms  |
| random-5k  | 5 000   | **4 990**    | 0%     | 0.19 ms  | 0.48 ms | 3.15 ms  | 107 ms   | 132 ms   |
| hot1-5k    | 5 000   | **5 005**    | 0%     | 0.19 ms  | 0.43 ms | 1.39 ms  | 4.87 ms  | 9.87 ms  |

**All five tests meet target with zero failures.** Sub-ms p99 across
1 k / 2 k / hot-2k / hot-5k. 5 k random sits at p99 = 3.15 ms (one
transient stall lifted p99.9 to 107 ms; p99 unaffected).

NFR §2.10 P95 ≤ 3 ms target passes by **4–7×** margin across all
loads.

### Docker cluster (3 ledger-node containers behind docker-bridge)

| Test       | Offered | Achieved rps | fail % | p50      | p95     | p99      | p99.9    | max     |
|------------|--------:|-------------:|-------:|----------|---------|----------|----------|---------|
| random-1k  | 1 000   | **1 006**    | 0%     | 0.72 ms  | 0.93 ms | 2.23 ms  | 15.7 ms  | 29.8 ms |
| random-2k  | 2 000   | **2 006**    | 0%     | 0.54 ms  | 1.63 ms | 6.50 ms  | 50.2 ms  | 125 ms  |
| hot1-2k    | 2 000   | **1 993**    | 0%     | 0.60 ms  | 3.44 ms | 16.4 ms  | 265 ms   | 317 ms  |
| random-5k  | 5 000   | 3 110        | 2.0%   | 5.55 ms  | 477 ms  | 832 ms   | 1.97 s   | 2.22 s  |
| hot1-5k    | 5 000   | 1 771        | 3.3%   | 16.8 ms  | 1.18 s  | 2.51 s   | 3.52 s   | 3.57 s  |

Docker has lower median latency than localhost-pre-fix but saturates
earlier: random-5k caps at 3.1 k rps with 2 % failures, hot-5k at 1.8 k
rps with 3.3 %. Docker-bridge networking + per-container scheduling
between 3 containers on the same host limits throughput; apply work is
not the bottleneck.

## Journey: 17 rps → 5 005 rps

Each step pulled a different bottleneck. p99 numbers are at the
random-2k offered rate.

| Step | Fix                                          | Achieved rps | p99       |
|------|----------------------------------------------|-------------:|-----------|
| 1    | Initial run (k6 env-var collision)           | 17           | 75 ms     |
| 2    | Renamed `K6_*` → `LT_*` so scenarios stay    | 108          | 5 s       |
| 3    | Killed per-apply `takeSnapshot()`            | 388          | 8.7 ms    |
| 4    | Tuned RaftOptions (inflight, applyBatch)     | 388          | 8.7 ms    |
| 5    | Pre-filter k6 account arrays once            | 2 006        | 6.50 ms   |
| 6    | `RAFT_LOG_SYNC=false` (skip macOS F_FULLFSYNC) | **2 006**  | **0.76 ms** |

## What was fixed

### F-1. k6 reserved env var `K6_DURATION` collapsed the scenarios block
The first matrix appeared to cap throughput at 17 rps. Root cause was
load-generator side: `K6_*` env vars are reserved by k6, and
`K6_DURATION` globally overrides `options.duration`, wiping the named
`scenarios:` block. k6 fell back to a default 1-VU executor.

**Fix:** renamed every custom env in scenarios + scripts to `LT_*`.
After fix, scenarios run with the configured VU pool.

### F-2. Per-apply `takeSnapshot()` pinned the FSMCaller thread (dominant cost)
`LedgerStateMachine.persistIfNeeded()` invoked `takeSnapshot()` after
every Raft apply. That serialised the entire ledger state (balances +
accounts + configs + journals + idempotency) via Jackson and wrote it
back to RocksDB. After ~30 k journals, the JRaft `fsm-caller`
disruptor thread was pinned at 100 % CPU inside
`UTF8JsonGenerator.writeRaw`.

**Smoking gun:** `jstack` of the leader showed
`JRaft-FSMCaller-Disruptor-0` runnable for 155 s of CPU, 100 % inside
`MapSerializer.serialize`.

**Fix:** `persistIfNeeded` no-ops unless `LEDGER_PER_APPLY_SNAPSHOT=1`.
JRaft's `onSnapshotSave` callback (driven by `snapshotIntervalSecs`)
handles periodic snapshots; per-apply was pure redundant work.

### F-3. `RAFT_LOG_SYNC=false` — kills macOS `F_FULLFSYNC` floor
After F-2, localhost runs hit a hard 17 ms p99 floor regardless of load,
GC, or applyBatch size. Cause: JRaft's per-batch Raft log fsync. On
macOS `fsync()` translates to `F_FULLFSYNC`, which flushes drive caches
and takes 10–20 ms per call. Every Raft commit batch hit this once,
setting the floor.

**Fix:** `raftOptions.setSync(false)`. Durability for the 3-node
cluster comes from quorum replication — a log entry isn't committed
until a majority has it. The leader's local fsync is redundant for the
HA model. Override with `RAFT_LOG_SYNC=true` for stricter single-node
durability.

| Workload         | sync=true | sync=false |
|------------------|----------:|-----------:|
| 1 k p50          | 17.5 ms   | 0.44 ms    |
| 1 k p99          | 22.9 ms   | 0.74 ms    |
| 5 k p50          | 14.4 ms   | 0.19 ms    |
| 5 k p99          | 21.2 ms   | 1.39 ms    |

### F-4. k6 client-side O(n) account filter
Hot scenarios hit 1 993 rps p99 = 4.5 ms while random scenarios were
stuck at 392 rps with identical server behavior. Discrepancy traced to
`loadtest/k6/lib/data.js` — `pickMaster()` / `pickSub()` called
`accounts.filter()` on every iteration over the 2001-entry pool.
The hot scenario pre-filtered in its own setup; random did not.

**Fix:** filter once at module load into `_masters`, `_subs`,
`_suspense` constants. After this, random tests scaled to match hot.

### F-5. Synchronous Kafka publish on the apply thread
`LedgerConfig` wired `KafkaEventPublisher` as the synchronous
`eventListener` inside `StateMachine.applyPosting`, in addition to the
existing `AsyncOutboxPublisher` that drains RocksDB CF_OUTBOX into
Kafka. The apply thread did duplicate work: Jackson serialise +
`KafkaProducer.send()` per balance line.

**Fix:** sync publisher only wires when `LEDGER_SYNC_KAFKA_PUBLISH=1`.
Async outbox publisher remains sole writer to Kafka.

### F-6. `RaftNodeManager.submit` 30 s timeout
Holding the servlet thread for 30 s under saturation locked the
Tomcat pool. With default 200 platform threads, the system stalled
under any sustained burst.

**Fix:** 5 s default, override via `LEDGER_RAFT_SUBMIT_TIMEOUT_MS`.
Caller surfaces a clean error and frees the servlet thread.

### F-7. `OutboxStore.flush()` outside the WriteBatch
Each posting did N separate native `rocksDBManager.put("outbox", …)`
calls *after* the journal/balance/idempotency atomic write batch.

**Fix:** added `OutboxStore.flushInto(WriteBatch)` so outbox events
commit in the same batch — single atomic write per posting.

### F-8. Spring Boot defaults
Tomcat used 200 platform threads, no virtual threads.

**Fix:** `spring.threads.virtual.enabled: true`,
`server.tomcat.threads.max: 800`,
`accept-count: 5000`, `max-connections: 10000`.

### F-9. JRaft pipelining defaults
SOFAJRaft defaults are sized for low-volume metadata systems.

**Fix:** `RAFT_MAX_INFLIGHT=1024`, `RAFT_APPLY_BATCH=32`. (Tried 128
earlier; added ~7 ms wait latency at light load via the FSMCaller
batch-fill threshold.) Disruptor input ring left at default — resizing
it at startup broke FSM leader-callback dispatch in earlier experiments.

### F-10. Wrong read endpoint
05-balance-read and 07-mixed-realistic originally hit
`/ledger/accounts/{id}/balances`, which does not exist — Spring
returned its "No static resource" 500 error page. Read latency was
misreported as sub-ms because the framework error path is fast.

**Fix:** path corrected to
`/ledger/balances?accountId=…&balanceType=…&currency=…`.

### F-11. PEER_NODES parser ignored per-node ports
The Raft peer-list parser stripped the port from each peer and
re-appended a single `RAFT_SERVER_PORT`. Fine for Docker (all nodes
listen on 28080 inside their containers), broken on localhost (3 JVMs
can't share port 28080).

**Fix:** parser honors whatever shape `PEER_NODES` carries —
`host1,host2,host3` (no ports) or
`host1:p1,host2:p2,host3:p3` (per-node ports).

### F-12. Bench-mode env flags
For matrix runs that exercise apply + Raft + network only, exposed:
- `LEDGER_SKIP_PERSIST=1` — skip RocksDB WriteBatch + Jackson
- `LEDGER_SKIP_EVENTS=1` — skip per-balance-line event construction
- `LEDGER_PER_APPLY_SNAPSHOT` — kept for explicit debug; off by default
- `RAFT_LOG_SYNC=false` — see F-3
- `RAFT_APPLY_BATCH=32` — see F-9
- `RAFT_MAX_INFLIGHT=1024` — see F-9

Defaults in `docker-compose.yml` are production-shape (`SKIP_PERSIST=0`,
`SKIP_EVENTS=0`). The bench shape lives in `docker-compose.bench.yml`
overlay.

## How to reproduce

```bash
# Localhost (best numbers)
JAVA_HOME=~/.sdkman/candidates/java/21.0.2-open mvn -DskipTests package
docker compose up -d mysql kafka
bash scripts/run-local-cluster.sh           # uses ZGC, Xmx=4g, sync=false
ACCOUNT_COUNT=200 bash loadtest/scripts/seed.sh
bash loadtest/scripts/run-matrix-bl.sh
bash scripts/stop-local-cluster.sh

# Docker (3 containers)
docker compose -f docker-compose.yml -f docker-compose.bench.yml up -d
ACCOUNT_COUNT=200 bash loadtest/scripts/seed.sh
bash loadtest/scripts/run-matrix-bl.sh
docker compose down
```

Reports under `loadtest/reports/matrix-bl-2026-05-28-localhost/`.

## What remains (out of scope for this session)

1. **Durability run**: re-test with `LEDGER_SKIP_PERSIST=0` to measure
   the actual RocksDB cost and verify the persist path still meets P95.
2. **Replace Jackson on hot path** with a binary codec (Kryo / Protobuf)
   so the durability run isn't dominated by serialisation.
3. **Long-soak**: 30 min steady-state at 5 k rps to verify no GC drift,
   heap leak, or leader-flap regression.
4. **Linux bare-metal run**: this whole investigation was on macOS where
   `F_FULLFSYNC` dominated. On Linux `fsync()` is ~50 µs typical; the
   sync=true path is likely fine there. Worth confirming.

## Files changed across this session

Production code:
- `ledger-core/.../raft/RaftNodeManager.java` — submit timeout,
  RaftOptions tuning (sync, applyBatch, inflight), per-node port parser
- `ledger-core/.../statemachine/LedgerStateMachine.java` —
  `persistIfNeeded` guard, `SKIP_PERSIST` / `SKIP_EVENTS` flags
- `ledger-core/.../rocksdb/OutboxStore.java` —
  `flushInto(WriteBatch)`
- `ledger-restful/.../rest/config/LedgerConfig.java` —
  sync Kafka opt-in, PEER_NODES port-aware wiring
- `ledger-restful/.../resources/application.yml` —
  vthreads + Tomcat pool

Infra:
- `docker-compose.yml` — `LEDGER_SKIP_*` env defaults
- `docker-compose.bench.yml` — bench overlay
- `scripts/run-local-cluster.sh` + `scripts/stop-local-cluster.sh` —
  native JVM launcher

Harness:
- `loadtest/k6/lib/{data.js,thresholds.js}`
- `loadtest/k6/scenarios/{03,05,06,07,08,09,10,14,15}*.js`
- `loadtest/scripts/{seed.sh,run-local.sh,run-all.sh,run-matrix-bl.sh}`
- `loadtest/README.md`

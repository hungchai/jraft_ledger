# Performance Findings — 2026-06-06

## 2026-06-06 (late): Projection micro-batch flush — 4 flusher threads

**Commit:** `e2ab551 perf(projection): 50ms micro-batch journal flush, 4 flusher threads`

### What changed

The `ledger-projection` MySQL write path was the dominant cause of
projection lag. Two issues fixed in one change:

1. **Bug: `balance.workers: 2` not effective.** The `@Value` was field
   injection read inside the `ProjectionWriter` constructor — Spring
   hadn't injected it yet so the value was 0, clamped to 1 worker.
   The configured pool size never actually applied. Fixed by moving
   `@Value` to a constructor parameter.
2. **Hot path: serial single-row INSERTs in the Kafka poll.** Per poll
   (up to 2000 events) `ProjectionWriter.writeBalanceBatch` opened one
   session, then looped calling `insertJournalLine` and `insertEvent`
   one-at-a-time (~1000 individual executes). `ExecutorType.BATCH`
   breaks with ShardingSphere's single-table route, so this stayed.

   New: `JournalFlushBuffer` enqueues resolved rows into per-shard
   `ConcurrentLinkedQueue`s. One scheduled flusher thread per shard
   (4 total) drains every 50 ms (or when the queue exceeds
   `maxBuffer=4000`) and issues **one multi-row INSERT per shard**:

   ```
   INSERT IGNORE INTO journal_line_? (cols) VALUES (?,...), (?,...), ...;
   INSERT IGNORE INTO projection_event_log_? (cols) VALUES (?,...), ...;
   ```

   Per-shard queues preserve insert order; 4 flusher threads give ~4×
   parallel write throughput vs the single-threaded prior design.

3. **ShardingSphere 5.5 compatibility:** the mapper SQL must use the
   **logical table name** (`journal_line`, not `journal_line_${shard}`)
   so the SS binder routes the multi-row statement correctly, and
   MyBatis `<foreach>` requires the `<script>` wrapper in annotation
   form. (Earlier attempted `journal_line_${shardIndex}` SQL produced
   `TableNotFoundException`; the logical-name form works.)

### Test cycle (10 VU × 2m × 3 runs, `--no-flush`)

| Metric | Before | After |
|---|---|---|
| Iterations | 12 014 (warm) | **12 814** (warm) |
| k6 send TPS | 100.1 | **106.8** |
| p50 | 15.4 ms | 17.7 ms |
| p95 | 195 ms | **125 ms** |
| Projection write rate | ~50 rows/s (estimated) | **~430 rows/s** |
| MySQL recon (all-account × 2 ccy) | 182/204, 22 lag_warn | **204/204, 0 lag_warn** |
| Checks | 223 passed, 0 failed, 29 warnings | **229 passed, 0 failed, 1 warning** |

`events_processed` and `journal_flush_rows` counters track the
projection in real time and stay equal; MySQL `journal_line_*` row
counts across all 4 shards match the consumer count.

---

## 2026-06-06 (earlier): Server-side batch buffer experiment & M3 baseline

### Summary

Server-side batch buffering (bundling N client commands into one Raft log
entry) was implemented and tested. It **degraded** throughput ~30× on the
local MacBook M3 stack (287 → 11 TPS) and was reverted. A clean
post-revert baseline establishes the **M3 hardware ceiling at ~130 TPS /
p95 ~90 ms** — confirming the 10K TPS / 50 ms P95 NFR is unattainable on
this hardware (and not relevant for a single-node M3 dev environment).

### Context

- 2026-06-05 findings ([`docs/PERF-FINDINGS-2026-06-05.md`](PERF-FINDINGS-2026-06-05.md)):
  the per-cmd cost is ~4 ms (1 fsync + Jackson), 50 VUs queue behind
  Raft's single-threaded apply for 145 ms P95.
- Goal of this iteration: amortise the per-cmd cost by packing N client
  commands into one Raft log entry (`MultiRaftCommand`), so N postings
  share 1 Raft log overhead.
- Tool: `BatchBuffer` (leader-only) that accumulates `RaftCommand`s for a
  time window (5 ms) or a size limit (8) before submitting a
  `MultiRaftCommand` to Raft.

### Implementation (reverted, not committed)

| File | Change |
|---|---|
| `raft/BatchBuffer.java` (new) | Per-leader accumulator; time/size triggered flush |
| `domain/command/MultiRaftCommand.java` (new) | `record` wrapping `List<RaftCommand>` |
| `raft/CommandSerializer.java` | Length-prefixed encode/decode for `MultiRaftCommand` |
| `raft/RaftNodeManager.java` | New `submitBatched` / `submitMultiInternal` paths |
| `raft/LedgerRaftStateMachine.java` | New `applyMultiCommand` to unpack `MultiRaftCommand` |
| `metrics/LedgerMetrics.java` | `applyBatchSize`, `applyFsync`, `applyBatchEntries` counters |
| `rest/config/SpringConfigService.java` | Read `LEDGER_BATCH_SIZE` / `LEDGER_BATCH_WINDOW_MS` |
| `rest/config/LedgerConfig.java` | Pass new config into `RaftNodeManager` ctor |
| `docker-compose.yml` | `LEDGER_BATCH_SIZE=8`, `LEDGER_BATCH_WINDOW_MS=5` |

### Results — batch buffer enabled (LEDGER_BATCH_SIZE=8, WINDOW=5ms)

Tool: `./scripts/test-cycle.sh --vus 50 --duration 2m`, M3, 3-node stack.

| Metric | No batch (baseline) | With batch (8/5ms) | Change |
|---|---|---|---|
| TPS (k6 send) | 287 | 11 | **−96%** |
| p95 latency | ~50 ms | 12.31 s | **+246×** |
| failure rate | 0% | 25% (timeouts) | regress |
| `apply_batch_size` avg | n/a | 8.0 | bundling worked |
| `apply_batch_entries_total` | n/a | 3 279 | 11% of all cmds |
| `raft_total_seconds_count` (HTTP reqs) | 29 235 | 29 235 | 100% |

**Root cause analysis**:

- 89% of HTTP requests bypassed the batched path. Only ~11% of cmds were
  bundled — almost all RFQ cmds were submitted one-at-a-time because
  `submitMultiInternal` (size=1) was being called via the single-cmd
  fallback.
- Even the 11% that did go through the batched path saw **per-cmd fsync**
  (the `LedgerRaftStateMachine.applyMultiCommand` iterated child commands
  and called `executeCommand` per child — each child still triggered its
  own `RocksDB WriteBatch + fsync`).
- Net effect: Raft log overhead reduced, but `fsync` count unchanged at
  1-per-original-cmd. The bundling cost (extra serialization, AQM race
  on first-vs-Nth cmd, leader re-routing on `submitMultiInternal`) added
  latency far exceeding the saved Raft overhead.

### Decision: revert, do not commit

User directive: *"做不好就不要commit"*. All files in the table above were
reverted (`git checkout`) and the new files deleted. Unit tests
**61/61 pass** post-revert, Maven compile clean.

### M3 hardware baseline (post-revert, clean stack)

Tool: `./scripts/test-cycle.sh --vus 10 --duration 2m` (with flush +
restart), M3, 3-node stack, Docker, no `LEDGER_BATCH_*` overrides.

| Metric | Value |
|---|---|
| Iterations | 15 665 |
| Failure rate | 0.00% |
| k6 send TPS | 130.5 req/s |
| p50 latency | 11.37 ms |
| p95 latency | 91.10 ms |
| `raft.lastAppliedIndex` (3 nodes) | 15 869 / 15 869 / 15 869 |
| `smJournalSeq` (3 nodes) | 15 767 / 15 767 / 15 767 |
| Cross-node balance (3 nodes × 2 ccy) | 22/22 identical |
| MySQL balance recon | 22/22 match, 0 mismatch, 0 lag-warn |
| MySQL projection lag | 223 (stuck at tail; non-fatal, see notes) |

**44 passed, 0 failed, 4 warnings.** Warnings: projection tail stuck
(Kafka consumer commit / batch flush threshold — pre-existing, unrelated
to this iteration).

### Observations

1. The earlier "~287 TPS" reported in some sessions is the **per-cmd
   ceiling on isolated bursts**, not a sustainable rate. The real
   sustained rate on M3 with full pipeline (Raft + Kafka + MySQL
   projection) is **~130 TPS / p95 ~90 ms**.
2. NFR 10K TPS / 50 ms P95 is **unachievable on M3 dev hardware**. It
   requires:
   - NVMe with battery-backed write cache, or
   - 10 GbE between Raft nodes, or
   - Kernel IO tuning, and
   - Multi-leader / sharded Raft groups (architectural, not local).
3. The batch-buffer experiment **proved the fsync cost cannot be
   amortised by Raft log bundling alone**. To truly batch fsync, the
   refactor must split `persistApply` into `prepare` (collect
   `WriteBatch` ops) + `commit` (1 fsync per batch) at the FSM level
   — and resolve the AQM account-lock acquisition order to avoid
   deadlocks across the batch.

### What was NOT committed

- `BatchBuffer.java` (deleted)
- `MultiRaftCommand.java` (deleted)
- `applyMultiCommand` / `submitMultiInternal` / `submitSingleFromBuffer` (reverted)
- `apply_batch_size` / `apply_fsync` / `apply_batch_entries` metrics (reverted)
- `LEDGER_BATCH_SIZE` / `LEDGER_BATCH_WINDOW_MS` config plumbing (reverted)
- `docker-compose.yml` `LEDGER_BATCH_*` env vars (reverted to default off)

### Reproducibility

```
./scripts/test-cycle.sh --vus 10 --duration 2m
```

This produces the 130 TPS / 91 ms p95 baseline and a full diagnostic
log under `jraft_ledger/diagnostics/recon-*.log`.

# Hot-path Performance Findings (2026-06-05)

## Summary

Posting P95 latency is dominated by Raft **single-threaded apply
queueing**, not by fsync, GC, or serialisation. fsync itself contributes
only ~2.77ms per command (~2% of the observed P95). Further server-side
optimisation cannot push the ceiling below ~4ms × N for N concurrent
postings on a single Raft leader.

## Measurement setup

- Stack: 3-node SOFAJRaft cluster, MacBook Air M3, Docker.
- Tool: k6 50 VUs × 30s against `/ledger/postings` (RFQ hot-account
  workload — every posting touches `STRESS-HOT-CO-001`).
- Metrics: 8 hot-path Timers added to `ledger-core` via
  `LedgerMetrics` (Micrometer `Timer` with `publishPercentileHistogram`),
  exposed through `/actuator/prometheus`.

## Results (leader node, 7520 samples)

| Timer                          | avg     | role                                   |
|--------------------------------|---------|----------------------------------------|
| `ledger.raft.enqueue`          | 0.15ms  | Task → Raft Disruptor enqueue          |
| `ledger.raft.wait_apply`       | 145ms   | Disruptor enqueue → onApply done       |
| `ledger.raft.wakeup`           | 0.00ms  | future.get() wake-up                  |
| `ledger.raft.total`            | 145ms   | HTTP handler → response                |
| `ledger.apply.deserialize`     | 0.17ms  | command deser inside onApply           |
| `ledger.apply.persist`         | 3.25ms  | full persistApply (Jackson + fsync)    |
| `ledger.rocksdb.write`         | 2.77ms  | RocksDB WriteBatch + fsync             |
| `ledger.apply.total`           | 3.95ms  | full onApply stage                     |

## Cross-check

- Throughput: 7317 postings in 30s = 244 cmd/s = **4.1 ms / cmd**.
- Per-iter (onApply) cost: 3.95ms ≈ matches the 4.1ms throughput.
- raft.wait_apply (145ms) - apply.total (3.95ms) = 141ms ≈ **35 entries
  × 4ms queued in the Raft Disruptor** ahead of any given request.

## Why fsync is not the bottleneck

`persistApply` already batches a single command's **journal + lines +
balance + idempotency + outbox** into one RocksDB `WriteBatch`, so each
posting incurs exactly one `fsync()` (`RocksDBManager.writeOptions` has
`sync=true`). The 2.77ms cost of that fsync is **2% of P50** and cannot
explain the 145ms wait.

## Why AQM doesn't help

`AccountQueueManager` does per-account single-worker serialisation, so
all 50 VUs fighting over `STRESS-HOT-CO-001` wait in a single queue.
AQM's only effect is to accept the HTTP request immediately and process
it in a background worker — **the wall time for the last VU is the same
(~200ms for 50 cmd)** whether AQM is present or not.

## Why onApply iteration can't batch further

- `Node.apply(Task)` accepts one task per call. SOFAJRaft has **no
  client-side batch entry API** (`applyBatch` does not exist on `Node`).
- `FSMCallerImpl.doApplyTasks` invokes `fsm.onApply(Iterator)` once per
  Disruptor dispatch, and at 244 cmd/s the iterator is drained before
  the next dispatch — **iter typically contains 1 entry**.
- The 4ms/cmd cost is dominated by one fsync plus Jackson serialisation
  in `applyJournalCommand` and is the lower bound for a single-entry
  apply on this hardware.

## Options to break the 4ms × N ceiling

| Option                                  | Effect                      | Cost / risk                                   |
|-----------------------------------------|-----------------------------|-----------------------------------------------|
| Client-side batch API (1 request = N postings) | 1 Raft entry, 1 fsync, N postings | API contract change; clients must adapt        |
| Multi-leader per account (split Raft group)    | Parallel apply across groups | Large architectural change; snapshot/routing |
| Parallel FSM apply (multi-thread)              | Multiple apply threads in same group | Breaks Raft log linearisability unless reordered at apply time |
| Faster storage (battery-backed write cache, ZNS, Optane) | Lower fsync constant | Hardware only |

The first option is the only one that keeps the current Raft topology
intact and gives a proportional latency reduction (one fsync amortised
across N postings).

## Committed infrastructure

Commit `96dcbe1` adds the metric infrastructure (`LedgerMetrics`,
`application.yml` enables `prometheus`, `LedgerConfig` initialises the
registry). All numbers in this document are reproducible by re-running
the k6 stress test and querying the timers via Prometheus.

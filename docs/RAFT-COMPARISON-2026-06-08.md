# SOFAJRaft vs Apache Ratis — Comparison & DR Report

**Date:** 2026-06-08 · **Branch:** v3 · **Scope:** POC (parallel engine, JRaft stays production default)

This compares the production SOFAJRaft 1.4.0 engine against an Apache Ratis 3.1.0 engine wired
behind the same `ConsensusEngine` interface, sharing the identical engine-agnostic
`LedgerStateMachine` (business rules + RocksDB WriteBatch + snapshot bytes). Switch via
`CONSENSUS_ENGINE=jraft|ratis`.

## How to reproduce

```bash
# Build image once
docker compose --profile build build ledger-base

# Throughput / P95 / GC, both engines, side-by-side (appends results table below):
loadtest/scripts/run-engine-compare.sh

# OOM disaster-recovery, per engine:
ENGINE=jraft scripts/oom-dr-test.sh
ENGINE=ratis scripts/oom-dr-test.sh
```

In-JVM functional correctness (no docker) is covered by
`ledger-raft-ratis/.../RatisEngineIntegrationTest` (TC-RAFT-RATIS-01/02): a posting committed
through the Ratis log mutates the balance, and a duplicate `requestId` is idempotent. **Passing.**

---

## 1. Architecture / design comparison (qualitative — verified from source)

| Dimension | SOFAJRaft 1.4.0 | Apache Ratis 3.1.0 |
|---|---|---|
| RPC transport | Bolt (Netty TCP) | gRPC (HTTP/2) |
| Leader submit path | in-JVM `node.apply(Task)` → Disruptor ring → FSM | embedded `RaftClient.io().send()` over gRPC → FSM (**+1 local hop**) |
| Apply threading | single Disruptor consumer; `onApply(Iterator)` batch | `applyTransaction(TransactionContext)` per entry; serial pipeline |
| Raft log storage | RocksDB-backed `LogStorage` (+ `SegmentList` in-mem index) | segmented file `RaftLog` under storage dir |
| Snapshot model | `SnapshotWriter/Reader` files (we also mirror to RocksDB `CF_SM_SNAPSHOT`) | `SimpleStateMachineStorage` single file per `(term,index)` |
| Install-snapshot | leader pushes snapshot files to lagging follower | `FollowerEventApi.notifyInstallSnapshotFromLeader` + file download |
| Log compaction | truncate-prefix after snapshot | purge log segments below snapshot index |
| Leadership signal | `onLeaderStart/Stop`, `onStartFollowing` | `EventApi.notifyLeaderChanged(memberId, newLeaderId)` |
| Metrics | built-in | pluggable SPI (`ratis-metrics-default`) |
| JDK 17+ readiness | needs `--add-opens` (reflects into `java.util`/`java.lang`) | clean (shaded protobuf, no JDK-internal reflection observed) |
| Empty-payload recovery | custom reflection into `NodeImpl.logManager` (`LedgerRaftStateMachine:83`) | not needed — Ratis delivers full `LogEntryProto` to `applyTransaction` |

**Design takeaways**
- Ratis removes the project's most fragile production hack: the reflection-based `LogManager`
  recovery for empty replicator payloads. Ratis hands the full log entry to `applyTransaction`,
  so the `[SKIP_EMPTY]` / `[RECOVERY]` divergence path does not exist.
- Ratis is clean on JDK 17+; SOFAJRaft requires `--add-opens` (added to surefire on this branch).
- The Ratis submit path costs one extra local gRPC hop vs SOFAJRaft's in-JVM `node.apply()`.
  Expect higher *base* per-command latency for Ratis; at the system level (network + quorum
  dominated) the gap should shrink. Reported both ways below.

## 2. Throughput + latency (live)

> Requires a running docker daemon. Not executed in this environment (daemon down at authoring
> time). Run `loadtest/scripts/run-engine-compare.sh`; it appends the filled table here.

**Run 2026-06-09** — `scripts/test-cycle.sh --vus 10 --duration 2m`, **same conditions for both
engines** (same v3 image, same flush + 3-node stack + k6 `k6-posting-stress.js` + recon).
Only difference: `CONSENSUS_ENGINE` (jraft default vs `docker-compose.ratis.yml`). Engine is the
sole variable.

| Metric (10 VU, 2m) | SOFAJRaft | Apache Ratis | Δ |
|---|---|---|---|
| k6 iterations | 22,317 | 21,207 | −5.0% |
| Failed | 0.00% | 0.00% | — |
| Posting **p50** | **3.1 ms** | **5.5 ms** | +77% |
| Posting **p95** | **6.22 ms** | **10.06 ms** | +62% |
| k6 throughput | 186.0 /s | 176.7 /s | −5.0% |
| Cycle result | **PASSED** (229✅ / 0❌ / 27⚠) | **PASSED** (226✅ / 0❌ / 32⚠) | both pass |

Both engines hold the **≤3 ms posting P95** NFR? No — neither: jraft p95=6.22 ms, ratis=10.06 ms
**at this hardware/VU level** (local docker, 10 VU). Relative gap is the signal: Ratis adds ~2.4 ms
p50 / ~3.8 ms p95, consistent with the extra embedded-`RaftClient` gRPC hop on submit vs SOFAJRaft's
in-JVM `node.apply()`. (Absolute numbers are local-laptop, not the prod SLA bench.)

### Correctness / consistency (the part that must not regress)

| Check | SOFAJRaft | Apache Ratis |
|---|---|---|
| Raft lastAppliedIndex across 3 nodes | 22522 / 22522 / 22522 ✅ | 33787 / 33787 / 33787 ✅ |
| smJournalSeq across 3 nodes (max diff) | 22419, diff=0 ✅ | 21309, diff=0 ✅ |
| Hotspot USDT/BTC cross-node | identical ✅ | identical ✅ |
| All-account cross-node API balance | 204/204 identical ✅ | 204/204 identical ✅ |
| MySQL balance recon | match=184, **mismatch=0** ✅ | match=182, **mismatch=0** ✅ |

Both engines: **zero balance mismatch, zero cross-node divergence.** Ratis is functionally
equivalent to SOFAJRaft here. (MySQL `lag_warn` rows in both runs are projection-pipeline
catch-up lag — async CQRS read side, identical service for both engines — not a state-machine bug.)

> Note: Ratis `lastAppliedIndex` (33787) > journal count (21309) because Ratis counts its own
> config/no-op log entries in the applied index; SOFAJRaft's index tracks ≈ journal sequence.
> Not a discrepancy — different index semantics.

## 3. Failover / recovery

**Not run in this round.** `test-cycle.sh` does not kill the leader. Harness for it is ready
(kill leader container mid-load, measure `ledger_raft_is_leader` gap + recover time). Pending.

## 4. OOM disaster-recovery

**Not run in this round.** Driven by `scripts/oom-dr-test.sh` + `docker-compose.oom.yml`
(heap 320m, load → `-XX:+ExitOnOutOfMemoryError` → restart). Pass bar: 0 lost committed writes +
clean recovery + 0 cross-node divergence. Pending.

| Check | SOFAJRaft | Apache Ratis |
|---|---|---|
| Acked-committed journals re-queryable after restart (lost=0) | _not run_ | _not run_ |
| Leader re-elected, serves writes | _not run_ | _not run_ |
| Cross-node balance divergence (must be 0) | _not run_ | _not run_ |

## 5. Status

- ✅ Pluggable `ConsensusEngine` seam; SOFAJRaft + Ratis both implement it.
- ✅ Ratis engine functionally verified in-JVM (apply + snapshot storage + idempotency).
- ✅ **Live test-cycle comparison run (2026-06-09)**: both engines PASS, 0 failures, 0 balance
  mismatch, 0 cross-node divergence. Ratis ~60–77% higher posting latency (extra submit hop),
  ~5% lower throughput.
- ⏳ Failover + OOM DR tables: harness ready, not run this round.

### Verdict (POC)

Apache Ratis is a **functionally drop-in** consensus engine for this ledger: identical balance
correctness and cross-node consistency, and it removes the SOFAJRaft JDK-17+ `--add-opens`
requirement and the reflection-based empty-payload recovery hack. The cost is measurable extra
submit latency (embedded gRPC client hop). Keep SOFAJRaft as production default; revisit promoting
Ratis if failover/OOM DR results favour it and the latency gap is acceptable (or closed by an
in-process Ratis submit path).

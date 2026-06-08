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

| Metric | SOFAJRaft | Apache Ratis |
|---|---|---|
| Posting P95 (s) | _pending_ | _pending_ |
| Posting TPS | _pending_ | _pending_ |
| Max GC pause rate (s/s) | _pending_ | _pending_ |

Scenarios: `03-internal-transfer`, `07-mixed-realistic`, `08-burst-5k` (k6, 1000 accounts).

## 3. Failover / recovery (live)

Kill the leader container mid-load; measure election gap (`ledger_raft_is_leader` flat-line),
time-to-recover-TPS, and install-snapshot duration on rejoin. _Pending live run._

## 4. OOM disaster-recovery (live)

Pass bar: **0 lost committed writes + clean recovery + cross-node balance match.** Driven by
`scripts/oom-dr-test.sh` (heap throttled to 320m via `docker-compose.oom.yml`; load until OOM →
`-XX:+ExitOnOutOfMemoryError` → `restart: unless-stopped`).

| Check | SOFAJRaft | Apache Ratis |
|---|---|---|
| Acked-committed journals re-queryable after restart (lost=0) | _pending_ | _pending_ |
| Leader re-elected, serves writes | _pending_ | _pending_ |
| Cross-node balance divergence (must be 0) | _pending_ | _pending_ |
| Time-to-OOM / time-to-recover | _pending_ | _pending_ |

## 5. Status

- ✅ Pluggable `ConsensusEngine` seam; SOFAJRaft + Ratis both implement it.
- ✅ Ratis engine functionally verified in-JVM (apply + snapshot storage + idempotency).
- ⏳ Live throughput / failover / OOM tables: harness ready, awaiting a docker host.

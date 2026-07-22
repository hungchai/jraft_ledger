# Apache Ratis POC + DR/OOM Comparison vs SOFAJRaft — Plan

**Date:** 2026-06-08
**Branch:** `ratis-spike`
**Goal:** Parallel Apache Ratis consensus engine alongside existing SOFAJRaft 1.4.0. Benchmark both. Run OOM DR test on both. Keep JRaft as production default — Ratis is selectable, non-destructive.

---

## Decisions (confirmed)

| Topic | Decision |
|---|---|
| Ratis scope | Parallel POC/spike. New module `ledger-raft-ratis`. Reuse `LedgerStateMachine` apply logic. Keep JRaft intact. |
| Compare on | (1) Throughput + P95 latency (2) Failover / recovery time (3) Memory / GC footprint (4) Snapshot + log model |
| OOM/DR pass bar | Force OOM under load → kill → restart → **0 lost committed writes** + clean rejoin + balances reconcile vs RocksDB/MySQL |

## Defaults chosen (state, not blocking — flag if you disagree)

- **Ratis version:** latest stable `3.1.x` (Maven `org.apache.ratis:ratis-server` + `ratis-grpc`, gRPC transport — closest analog to JRaft Bolt RPC).
- **Submit path:** embedded `RaftClient` pointed at the local server group (Ratis idiom; no in-JVM `node.apply()` equivalent). Same `submit(RaftCommand)→CommandResult` surface.
- **Benchmark env:** local 3-node docker stack reusing existing `docker-compose.yml` + k6 `loadtest/`. New compose override per engine.
- **Engine select:** Spring property `ledger.consensus.engine=jraft` (default) `|ratis`.

---

## The consensus seam (already clean)

SOFAJRaft is isolated to 3 classes; only 2 are engine-specific:

```
LedgerStateMachine            ← engine-AGNOSTIC core. apply*/snapshotBytes/restoreFromBytes.
   ▲                            RocksDB + business rules. REUSED UNCHANGED by both engines.
   │ wraps
LedgerRaftStateMachine        ← JRaft adapter (StateMachineAdapter). onApply/onSnapshot*/leader cbs.
RaftNodeManager               ← JRaft lifecycle + submit()/isLeader()/getLeaderEndpoint()/close().
   ▲
   │ raftNodeManager::submit  (Function<RaftCommand,CommandResult>)
AccountQueueManager           ← consumer. Engine-blind already.
```

Selection point: `LedgerConfig.raftNodeManager()` bean (`ledger-restful/.../LedgerConfig.java:215`).

---

## Phase 0 — Extract `ConsensusEngine` interface (ledger-core, small)

Define interface both engines satisfy. No behavior change for JRaft.

```java
public interface ConsensusEngine extends AutoCloseable {
    CommandResult submit(RaftCommand cmd);
    boolean isLeader();
    String getLeaderEndpoint();
    long getLastAppliedIndex();
    void close();
}
```

- `RaftNodeManager implements ConsensusEngine` (already has every method — pure declaration).
- `AccountQueueManager`, `ClusterController`, controllers depend on `ConsensusEngine` not `RaftNodeManager`.
- **Verify:** `mvn -pl ledger-core,ledger-restful compile` green; `mvn test` unchanged green.

## Phase 1 — `ledger-raft-ratis` module

New Maven module, depends on `ledger-core` (gets `LedgerStateMachine`, `RaftCommand`, `CommandSerializer`, `CommandResult`, `NodeRole`). Ratis deps scoped here only — no clash with JRaft in ledger-core.

1. `RatisLedgerStateMachine extends BaseStateMachine`
   - `applyTransaction(trx)` → deserialize → `executeCommand` (mirror of `LedgerRaftStateMachine.executeCommand`) → `ledgerStateMachine.apply*` → return result message. Complete pending future.
   - `takeSnapshot()` → `ledgerStateMachine.snapshotBytes()` → write Ratis snapshot file; also `ledgerStateMachine.takeSnapshot()` (local RocksDB).
   - `reinitialize()`/snapshot load → `restoreFromBytes`.
   - Leader change listener → `NodeRole.setLeader/setFollower`.
   - Reuse `CommandSerializer` verbatim.
2. `RatisNodeManager implements ConsensusEngine`
   - `RaftServer.newBuilder` with `RaftGroup` from `PEER_NODES`, gRPC transport, storage dir `LEDGER_RAFT_DATA_PATH/ratis`.
   - `submit()` via embedded `RaftClient.io().send(Message)`; same 10s timeout + metrics hooks as JRaft (`LedgerMetrics.recordRaft*`).
   - `isLeader/getLeaderEndpoint/close`.
3. `LedgerConfig` — branch on `ledger.consensus.engine`; build `RatisNodeManager` when `ratis`, else existing `RaftNodeManager`. Both return `ConsensusEngine`.
4. Reuse existing Raft Prometheus gauges (`ledger.raft.is_leader`, `last_applied_index`) — wire to Ratis FSM.
- **Verify:** boot 3-node Ratis stack locally; smoke-test.sh green (posting + balance + idempotency + leader election).

## Phase 2 — Benchmark harness (both engines, identical conditions)

Reuse `loadtest/` k6 + existing micrometer/Prometheus/Grafana.

| Metric | How |
|---|---|
| TPS + P95 posting | k6 scenarios `03-internal-transfer`, `07-mixed-realistic`, `08-burst-5k`, `09-burst-10k`; read `ledger.posting.duration` + `ledger.raft.*` |
| Failover / recovery | Kill leader container mid-load; measure election time (gap in `is_leader`) + time-to-recover-TPS + snapshot-install time on rejoin |
| Memory / GC | JVM micrometer (`jvm.gc.pause`, `jvm.memory.used`) + optional JFR; sustained `07-mixed-realistic` |
| Snapshot + log model | Doc: on-disk log+snapshot size after fixed N commits; compaction behavior; install-snapshot mechanics. Measured, not just paper. |

- Run matrix: `scripts/run-matrix-bl.sh` style, once per engine via compose override.
- Output: `docs/RAFT-COMPARISON-<date>.md` with side-by-side tables + Grafana screenshots.

## Phase 3 — OOM DR test (both engines)

Pass bar: **0 lost committed writes + clean recovery + recon match.**

1. Compose override: leader `-Xmx` small (start ~256m, tune down until OOM reproduces under load). `-XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError`.
2. k6 sustained load; record every acked `journalId` (k6 already returns it) → expected-commit set.
3. Leader OOMs + exits → container restarts → node rejoins, replays log / installs snapshot.
4. **Assertions:**
   - RocksDB journal count on recovered node == expected-commit set (no committed loss).
   - MySQL projection reconciles (existing recon path) within tolerance.
   - Balances per account match across all 3 nodes (no divergence).
   - Cluster returns to healthy leader within bound.
5. Repeat identically for JRaft and Ratis. Compare: time-to-OOM, recovery time, replay duration, integrity result.
- Output: appended to `docs/RAFT-COMPARISON-<date>.md` DR section.

---

## CLAUDE.md compliance

- **ADR:** new `ADR-00x: Pluggable Consensus Engine (SOFAJRaft | Apache Ratis)` in `LEDGER-PLATFORM-FULL-REQUIREMENTS.md` + version bump.
- **Test cases:** `TC-RAFT-xx` for Ratis apply/snapshot/replay/failover + `TC-NFR-xx` for OOM DR. Update `TDD-TEST-CASES.md`.
- **No StateMachine.apply bypass:** Ratis path still goes Leader→submit→FSM apply→RocksDB WriteBatch. Unchanged invariant.
- `mvn clean compile` + `mvn test` green before done.

## Risks

- Ratis embedded-client submit adds a hop vs JRaft local `node.apply()` — may show higher base latency; that's a real comparison datapoint, not a bug.
- gRPC vs Bolt RPC — different wire/threading; GC profile will differ (expected).
- Reflection-based `LogManager` recovery (JRaft-specific, `LedgerRaftStateMachine:83`) has no Ratis analog needed — Ratis handles entry delivery differently.

## Rough size

Phase 0: ~1 interface + small edits. Phase 1: ~2 classes (~400 LOC) + module + config branch. Phase 2–3: scripts + compose overrides + comparison doc. Largest risk/time = Ratis API learning + OOM tuning.

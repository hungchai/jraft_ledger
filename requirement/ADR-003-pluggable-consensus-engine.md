# ADR-003 Pluggable Consensus Engine: SOFAJRaft | Apache Ratis

**決策狀態**: Proposed (POC)
**決策日期**: 2026-06-08
**決策人**: Ledger Platform Team
**影響範圍**: ADR-001 (Raft + CQRS), F-002 Posting, F-008 State Machine, F-011 BalanceChangeEvent, NFR (performance / DR)

> POC on branch `v3`. SOFAJRaft 1.4.0 remains the production default; Apache Ratis 3.1.0 is a
> selectable alternative gated behind `CONSENSUS_ENGINE`. No production traffic change.

---

## 1. Background

The ledger's consensus layer is SOFAJRaft 1.4.0 (Bolt RPC). Two pressures motivated evaluating
Apache Ratis as an alternative:

1. **JDK 17+ friction** — SOFAJRaft reflects into JDK internals (`SegmentList` →
   `java.util.ArrayList.elementData`, `ThrowUtil` → `java.lang.Throwable.cause`), requiring
   `--add-opens` to run/test on the project's JDK 21.
2. **A production correctness hack** — `LedgerRaftStateMachine` recovers empty replicator
   payloads via reflection into `NodeImpl.logManager`; failure to recover risks balance
   divergence (`[SKIP_EMPTY]`). We wanted to know if a different engine removes this class of bug.

## 2. Decision

Introduce a `ConsensusEngine` interface in `ledger-core` that both engines implement. The
business logic — `LedgerStateMachine` (validation, balance mutation, RocksDB WriteBatch, snapshot
bytes, idempotency) — is **engine-agnostic and shared unchanged**. Only the thin Raft adapter and
the node lifecycle differ per engine.

```
LedgerStateMachine                    ← shared, engine-agnostic core
  ├─ LedgerRaftStateMachine + RaftNodeManager      (SOFAJRaft — implements ConsensusEngine)
  └─ RatisLedgerStateMachine + RatisNodeManager    (Apache Ratis — implements ConsensusEngine)
```

- New Maven module `ledger-raft-ratis` (Ratis deps isolated there).
- Selection at wiring time: `CONSENSUS_ENGINE=jraft` (default) `| ratis` →
  `LedgerConfig.raftNodeManager()` returns the chosen `ConsensusEngine`.
- The Account Queue, controllers, and cluster endpoints depend only on `ConsensusEngine`, so no
  engine API leaks past the `raft` package.
- **Invariant preserved**: all balance mutations still flow Leader → submit → state-machine apply
  → RocksDB WriteBatch. Ratis adds one embedded-`RaftClient` gRPC hop on submit (no in-JVM
  `node.apply()` equivalent); this is a measured trade-off, not a correctness change.

## 3. Rationale

| Factor | SOFAJRaft 1.4.0 | Apache Ratis 3.1.0 |
|---|---|---|
| Transport | Bolt (Netty TCP) | gRPC (HTTP/2) |
| Submit | in-JVM `node.apply()` | embedded `RaftClient.io().send()` (+1 local hop) |
| Snapshot | SnapshotWriter/Reader files (+ our RocksDB `CF_SM_SNAPSHOT`) | `SimpleStateMachineStorage` single file per `(term,index)` |
| Empty-payload recovery hack | required (reflection) | **not needed** — full `LogEntryProto` delivered to `applyTransaction` |
| JDK 17+ | needs `--add-opens` | clean |
| Maturity in this codebase | production, battle-tested here | POC, functionally verified in-JVM |

Ratis removes the empty-payload reflection hack and the JDK `--add-opens` requirement, at the cost
of an extra local submit hop and being unproven in this system. Keeping both behind one interface
lets us benchmark apples-to-apples before any migration decision.

## 4. Verification

- **Functional (no docker)**: `RatisEngineIntegrationTest` (TC-RAFT-RATIS-01/02) — posting commits
  through the Ratis log and mutates balance; duplicate `requestId` is idempotent. Passing.
- **Throughput / failover / GC / snapshot model**: `loadtest/scripts/run-engine-compare.sh` →
  `docs/RAFT-COMPARISON-2026-06-08.md`.
- **OOM DR (pass bar: 0 committed-write loss + clean recovery + no cross-node divergence)**:
  `scripts/oom-dr-test.sh` with `docker-compose.oom.yml`, run per engine.

## 5. Status / open items

- Live benchmark + OOM tables are pending a docker host (harness complete; daemon down at authoring).
- If Ratis wins on DR robustness + JDK cleanliness without a material throughput regression, a
  follow-up ADR will propose promoting it from POC to default.
- Ratis peer ids default to the docker hostname (== `NODE_ID`); revisit if NODE_ID ≠ hostname.

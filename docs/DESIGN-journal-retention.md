# Design: Journal Retention / Pruning (bound RocksDB + disk growth)

**Branch (proposed):** `fix/journal-retention` → PR to `v3-fix`
**Date:** 2026-06-16
**Status:** design (not yet implemented)

## Problem

The `journal` / `journal_line` RocksDB column families are **append-only and never
pruned**. Even after the heap/page-cache/native OOM fixes (which bound *RAM* to
O(working-set)), the **on-disk DB and its derived overhead grow without bound** with
cumulative postings:

- disk fills → writes fail → crash (days–weeks, slow but fatal);
- bigger DB → heavier compaction, higher read amplification, slower restart-replay;
- in K8s this also keeps nudging the pod's memory (index/filter/compaction scale with DB)
  toward the limit over time.

This is the **second face** of the same unbounded-growth class as the heap `journalStore`
bug: there we bounded memory; here we must bound the *data*.

## Decisions (product/architecture — confirmed)

1. **Hot-store retention = by raftIndex lag** (deterministic, identical on every node).
2. **Cold store = the MySQL projection** (journal + journal_line tables, already populated
   via Kafka). No new external store; "archive" = "already in the projection".
3. **Reversal window = recent only** (within a few days / current accounting period).
   ⇒ hot retention ≥ reversal window ⇒ **a pruned journal can never be the target of a
   reversal** — this removes the need for any cold-store reversal read-path.

## Design

### Retention watermark (deterministic)

Keep journals whose `raftIndex ≥ pruneBelow`, where
```
pruneBelow = lastSnapshotIndex − LEDGER_JOURNAL_RETENTION
```
- `LEDGER_JOURNAL_RETENTION` (default e.g. 1,000,000 journals) is a generous lag buffer that
  comfortably exceeds (a) the reversal window and (b) the Kafka→projection lag, so anything
  pruned is already durably in the MySQL projection.
- Tied to `lastSnapshotIndex` (not wall clock) → **deterministic**: every node prunes the
  exact same set at the same applied state → no cross-node divergence.
- JNL ids already encode raftIndex (`JNL-%016d`), so the prune range is a clean key range.

### When pruning runs

- Hook into the existing **snapshot** path (`onSnapshotSave` / `takeSnapshot`) — snapshots
  are deterministic checkpoints and already the natural GC point. After a snapshot at index
  S, delete `journal` + `journal_line` keys with raftIndex < (S − RETENTION).
- Use a RocksDB `DeleteRange` over the key range (cheap, single tombstone) rather than
  per-key deletes; compaction reclaims space.
- Pruning is itself repl-deterministic: triggered at the same applied index on all nodes.

### Read paths after pruning

| Query | Source | Notes |
|---|---|---|
| reversal (apply path) | node RocksDB | target always within retention ⇒ always present |
| F-006 single journal, **recent** | node RocksDB | within retention |
| F-006 single/list, **archived** | **MySQL projection** | older than retention; served by the read/projection service (node has no MySQL — CQRS), or returns `ARCHIVED` hint with a pointer |
| balance (F-005) | balanceStore (heap) | unaffected — never pruned |

### Snapshot / fresh-follower interaction

- The SM snapshot streams journals from RocksDB (existing `streamJournalsTo`). After pruning,
  it streams only the **retained window** → a fresh InstallSnapshot follower gets recent
  journals (enough for reversal) + complete balances. Older journals live in the projection.
  Consistent with the retention model; snapshot stays bounded in size too.

### What is NEVER pruned

- `balance`, `account_meta`, `balance_type`, `idempotency` CFs — bounded by #accounts /
  dedup window, and required for correctness. (idempotency bound is a separate follow-up.)

## Invariants (must hold)

- **Determinism**: prune range keyed on raftIndex + triggered at a deterministic applied
  index. Never wall-clock. Violation → divergence.
- **Reversal safety**: `LEDGER_JOURNAL_RETENTION` MUST be ≥ the max reversal age (in journals).
  Enforce/document; if a reversal ever targets a pruned id → reject `JOURNAL_ARCHIVED`
  (should be impossible given the window, but fail safe).
- **Archive-before-delete**: retention buffer ≫ Kafka→projection lag, so pruned journals are
  already in MySQL. (Optionally gate prune on a projection-watermark feed later; the node has
  no MySQL connection, so the lag buffer is the pragmatic guarantee.)
- **Audit/compliance**: full history is retained in the MySQL projection (and can be exported
  from there to long-term archive per retention policy). Hot store is a recent-window cache.

## Out of scope / follow-ups

- Routing F-006 archived-journal queries to the projection service (CQRS read-side; node
  can't reach MySQL today).
- idempotencyStore bounding (separate, ~48MB, slow-growing).
- MySQL projection's own retention/partitioning (it becomes the long-term store → needs its
  own archival to truly cap disk; or treat MySQL as the compliance store with DB-side
  partition rotation).

## Effort

Implementation is moderate but **consensus-critical** (deterministic prune in the apply/
snapshot path) — needs TDD: prune determinism across nodes, reversal-of-near-window still
works, fresh-follower-after-prune, snapshot size bounded, disk reclaimed after compaction.

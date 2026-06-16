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

---

## Addendum — memory/OOM root cause found during soak validation (2026-06-17)

Validating retention under sustained ~1000-VU load surfaced a separate, more urgent OOM
that was **not** disk/heap. Layered findings, in the order they were isolated:

### 1. On-disk growth was WAL, not journal data
`du` on the RocksDB dir showed 2.3GB while live SST (actual data) was only ~40MB —
retention was working. The 2.3GB was **WAL `.log` files**: with 9 column families a WAL
can't be deleted until the *oldest-unflushed* CF flushes past it; rarely-written CFs
(balance_type, account_meta) pinned old WALs.
**Fix:** `RocksDBManager` sets `setMaxTotalWalSize` (`LEDGER_ROCKSDB_MAX_WAL_MB`, default
512) → forces flush of pinning CFs so WALs are reclaimed. WAL then oscillates in a bounded
band instead of climbing monotonically.

### 2. RSS hitting the cgroup ceiling was reclaimable page cache (benign)
`memory.current` reached ~99% under load but **zero OOM-kills** across dozens of runs.
Breakdown (`memory.stat`): `file` (page cache) ≈ 2.2GB and **reclaimable** — the kernel
evicts it before any kill. Only `anon` (unreclaimable) is a real OOM risk. WAL writes are
buffered (not direct I/O), so WAL size drives page cache; the WAL cap also trims this.

### 3. The real OOM — a native `WriteBatch` handle leak
`anon` ratcheted up monotonically to ~4GB and never released, even after a forced GC.
NMT (`NativeMemoryTracking`) showed the **JVM** side flat (heap committed 2GB, Internal/
direct ~6MB) — the growth was **off-NMT native** (`anon − NMT-committed` climbed +1.7GB).
Root cause: `LedgerStateMachine.persistApply` created `new WriteBatch()` per apply but
**never closed it**. RocksJava frees a `WriteBatch`'s off-heap buffer only on `close()`
(no GC finalizer), so every posting leaked one batch → native RSS grew unbounded under
load → cgroup OOM (manifesting as health-check-timeout restarts, `OOMKilled=false`).
**Fix:** wrap the `WriteBatch` in try-with-resources. After the fix, `anon` collapsed from
4GB-and-climbing to <1GB and **plateaus** (tracks heap-resident warmup, native gap flat).
Regression test: `TC-ROCKS-LEAK-01` (high-volume apply persists every journal).

### 4. jemalloc to bound native fragmentation/retention
Independently, glibc malloc retains freed memory under RocksDB's heavy multithreaded
malloc/free churn (`MALLOC_ARENA_MAX` caps arena *count*, not retention). The Docker image
preloads **jemalloc** (`LD_PRELOAD`, `MALLOC_CONF=lg_dirty_mult:5`) so freed native memory
returns to the OS promptly — defence-in-depth on top of the leak fix.

### Resulting bounds (all enforced)
| Component | Bound | Mechanism |
|---|---|---|
| Journal/JournalLine SST (disk) | retention window | index-triggered `pruneJournals` + `deleteFilesInRanges` |
| WAL | `LEDGER_ROCKSDB_MAX_WAL_MB` (512) | `setMaxTotalWalSize` |
| RocksDB block/index/filter cache | `LEDGER_ROCKSDB_CACHE_MB` (256) | shared `LRUCache` |
| JVM heap | `-Xmx` (2g) | ZGC |
| JVM direct memory | `-XX:MaxDirectMemorySize` (512m) | JVM |
| Native (WriteBatch) | **0 leak** | try-with-resources |
| Native fragmentation/retention | working set | jemalloc |

Net: `anon` (the only unreclaimable, OOM-causing memory) is bounded ≈ heap + small native,
flat under sustained load. Page cache fills the rest of the cgroup and is reclaimed on
demand.

### Config tuning shipped
`docker-compose.yml`: heap 3g→2g, `MaxDirectMemorySize=512m`, `LEDGER_ROCKSDB_MAX_WAL_MB=256`,
`MALLOC_ARENA_MAX=2`, `mem_limit=6g`. Retention/prune-every set low (50000/10000) to exercise
pruning quickly under test; raise for production per the reversal-window/projection-lag buffer.

# Design: Disk-backed journalStore (fix unbounded-heap OOM)

**Branch:** `fix/journalstore-disk-backed` → PR to `v3-fix`
**Date:** 2026-06-16

## Problem

`LedgerStateMachine` keeps **all** journals in an in-heap `ConcurrentHashMap<String,Journal>`
(`journalStore`) and **all** requestIds in `idempotencyStore` (`ConcurrentHashMap`, no
eviction). Under sustained write load the heap fills with historical state — proven OOM:
800 VUs / 3g heap → `OutOfMemoryError: Java heap space` in ~6 min (2.5GB dump). Heap top
consumers: `JournalLine` 1.26M, `Journal` 542K, `IdempotencyEntry` 759K + their Strings.
Followers OOM too (they apply all committed entries → same unbounded growth), so admission
control on the leader (separate change) does NOT fix this.

## Key facts (verified)

- Journals are **already durably in RocksDB** `journal` CF (full `Journal` incl. embedded
  `lines`, JSON, keyed by `journalId`) — written every apply in `persistApply`. The heap
  map is **redundant storage** + query index + snapshot source.
- Live balances come from `balanceStore` (keyed by account, **bounded by #accounts**) — NOT
  from journalStore. Bounding journalStore does **not** affect balance correctness or reads.
- `getJournalSequence()` returns `journalStore.size()` → feeds `smJournalSeq`
  (recon / convergence / test-cycle). Must become a monotonic counter.
- JNL ids derive from **raftIndex** (`JNL-%016d`), NOT from `size()` → eviction won't break ids.
- **Snapshot crux:** `onSnapshotSave` writes one file = `snapshotBytes()` which serializes
  the **entire** journalStore. RocksDB is NOT part of the jraft snapshot. InstallSnapshot
  ships this blob to new/lagging followers. → If we bound the heap map, the snapshot becomes
  incomplete and a fresh follower loses history → **reversal of a pre-snapshot journal breaks**.
  **Therefore bounding the heap and changing the snapshot are inseparable.**

## What actually needs full journal history (correctness)

| Consumer | Needs all journals? | New source |
|---|---|---|
| balance apply (before/after) | no | `balanceStore` (unchanged) |
| balance query (F-005) | no | `balanceStore` (unchanged) |
| reversal: look up original journal by id | **yes** | RocksDB `journal` CF point-read |
| reversal: write REVERSED status | yes (1 row) | RocksDB write (already happens) |
| journal query F-006 (byAccount/byRef/byRequestId) | yes, historical | **MySQL projection** (indexed, ≤30ms; RocksDB full-scan would violate NFR) |
| snapshot / InstallSnapshot (follower bootstrap) | **yes** | stream journals from RocksDB into the snapshot blob |

## Design

### journalStore → bounded read-through cache
- Replace the unbounded map with a bounded LRU (size `LEDGER_JOURNAL_CACHE_MAX`, default e.g.
  50k) fronting RocksDB `journal` CF.
- `getJournal(id)`: cache → miss → RocksDB point-read (full Journal incl. lines) → populate cache.
- apply: write RocksDB (already does) + put in cache. **No unbounded growth.**
- reversal (`applyReversal`): original lookup via `getJournal(id)` (cache/RocksDB); REVERSED
  status write already goes to RocksDB.

### journalSequence → monotonic AtomicLong
- A dedicated `AtomicLong journalSequence`, incremented per applied journal, **persisted** in
  the snapshot (already a field in SnapshotData) and restored from it (stop deriving from `size()`).
- `getJournalSequence()` returns the counter.

### F-006 queries → MySQL projection
- `getJournalsByAccount` / `byBusinessEventRef` / `byRequestId` / `getJournalChain`
  → query the MySQL projection via `JournalMapper` (indexed). FSM no longer scans a heap map.
- (FSM keeps only point-read-by-id from RocksDB for reversal + single-journal GET.)

### Snapshot: stream journals from RocksDB (NOT heap)
- `snapshotBytes()` / `onSnapshotSave`: serialize balances + accountMeta + config +
  idempotency + counters as today, **but stream journals from the RocksDB `journal` CF
  iterator into the snapshot file** (not from a heap map) — bounded memory during snapshot.
- `restoreFromBytes` / `onSnapshotLoad`: stream journals from the snapshot back into the
  RocksDB `journal` CF (+ warm a little cache); do **not** load all into heap.
- Keeps jraft's single-file snapshot model (low plumbing risk vs RocksDB-checkpoint approach),
  fixes both the heap **and** the snapshot-time OOM, and keeps InstallSnapshot complete.
- Determinism: keyed iteration over the CF → same content on every node.

### idempotencyStore → deterministic bound
- Bound by **insertion/appliedIndex order** (deterministic across nodes — same Raft apply
  order), NOT wall-clock TTL (`Instant.now()` is non-deterministic → divergence).
- Evict oldest beyond `LEDGER_IDEMPOTENCY_MAX` (default e.g. 1M). Recent-dedup window only.
- Snapshot serialization must preserve order (list, not unordered map) so post-restore
  eviction stays deterministic.

## Staging (each stage compiles + tests before the next)

1. **journalSequence → AtomicLong** (decouple from size()). Snapshot stores/restores it.
   Test: counter monotonic across apply + survives snapshot/restore.
2. **F-006 queries → MySQL projection** (FSM delegates). Test: query parity vs current.
3. **journalStore → bounded LRU + RocksDB point-read** (getJournal/reversal). Test:
   reversal of an evicted journal still works (RocksDB read); cache bounded under load.
4. **Snapshot streams journals from RocksDB** + restore streams back. Test: snapshot/replay
   parity; **InstallSnapshot to a fresh node → reversal-of-old-journal works**; takeSnapshot
   does not OOM at scale.
5. **idempotencyStore deterministic bound.** Test: dup-detect within window; eviction
   deterministic; no cross-node divergence after restore.
6. **E2E:** 800-VU hammer / 3g heap for 30 min → no OOM on **any** node; cross-node balance
   + journalSeq convergence; RPO=0 across an induced leader OOM/restart.

## Risks / invariants (must hold)

- **Determinism:** every eviction/bound keyed on Raft-deterministic order (appliedIndex /
  keyed iteration), never wall-clock or local timing. Violation → node divergence.
- **Snapshot completeness:** a node bootstrapped purely from InstallSnapshot must be able to
  reverse ANY historical journal. Stage 4 test is the gate.
- **NFR:** F-006 ≤30ms (MySQL indexed, not RocksDB scan); posting p95 unaffected (apply path
  only adds a bounded cache put + the RocksDB write it already did).
- **Balances untouched:** `balanceStore` stays fully in-heap (bounded by #accounts) — it is
  the live-state source and is not the leak.

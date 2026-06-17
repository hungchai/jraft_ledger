## [2026-05-30] Session Summary — Full Day

### Commit History
```
bcd520f fix(k6): increase seed amounts to prevent INSUFFICIENT_BALANCE exhaustion
f2e5215 feat: DECIMAL(34,16) schema + USDT 16dp stress test + BTC 8dp
d689edc feat: bootstrap SYSTEM_SEED + BANK account type + NFR v0.5 + projection lag alerts + k6 hardening
796ad1d perf(projection): async balance worker + conflation queue + journal dedup
```

### 1. SYSTEM_SEED bootstrap (d689edc)
- Added SYSTEM_SEED to `LedgerConfig` bootstrap (COMPANY, USD+BTC+USDT)
- Fixes k6 seed postings failing silently because SYSTEM_SEED didn't exist

### 2. BANK account type (d689edc)
- Added `BANK` to `AccountType` enum
- Added BANK to institutional bypass in `LedgerStateMachine` (2 locations)
- Bootstrap `BANK_SETTLEMENT` account (BANK, USD+BTC+USDT)
- BANK accounts allow negative (institutional bypass)

### 3. API allowNegative reflects enforcement (d689edc)
- `BalanceQueryService.effectiveAllowNegative()` — returns `true` for institutional accounts (COMPANY/NOSTRO/SUSPENSE/BANK)
- CLIENT returns config value correctly
- Fixed misleading API response

### 4. NFR v0.5 — Performance targets (d689edc)
- Production: Posting P95≤3ms, P99≤10ms
- Docker/Local: Posting P95≤50ms, P99≤100ms
- Updated k6 thresholds to match (corrected P95 from 20ms → 3ms per NFR v0.6)

### 5. Projection lag monitoring (d689edc)
- `ledger.projection.seconds.since.last.event` Gauge in ProjectionConsumer
- `ledger.projection.events.processed` counter
- Alert rules: ProjectionLagHigh (>10s), ProjectionLagCritical (>30s)

### 6. k6 stress script hardening (d689edc)
- setup() checks every POST with `setupPost()` helper → aborts on hotspot seed failure
- `getLeaderUrl()` auto-refreshes every 10s + on connection failure
- Retry with exponential backoff (3 retries: 100/200/400ms) for 429/503/504/0

### 7. DECIMAL(34,16) schema (f2e5215)
- Widened 6 DECIMAL columns: account_balance (amount, frozen, locked), journal_line (amount, balance_before, balance_after)
- Supports USDT 16dp + BTC 8dp

### 8. USDT 16dp stress test (f2e5215)
- k6 script uses USDT (16dp) + BTC (8dp) instead of USD (2dp) + BTC
- 1 BTC = 73,091.09 USDT (user-updated)
- Trade size: 100 USDT = ~0.00136815 BTC

### 9. Seed amounts increased (bcd520f)
- Hotspot: 5000 BTC, 200M USDT
- Client: 10 BTC, 1M USDT each
- Prevents INSUFFICIENT_BALANCE in stress tests

### 10. Projection optimization (796ad1d)
- **ConflationQueue**: lock-free, latest accountSeq per key survives
- **Balance worker**: dedicated thread with BATCH-mode MySQL upserts
- **Journal PK cache**: deduplicates journal inserts (4 events share 1 journal)
- **INSERT IGNORE**: all 3 mappers (journal, journal_line, event_log) — zero duplicate crashes
- Throughput: 3 jnl/s → 77-119 jnl/s (30-40x), zero lag, exact balance match

### Key Learnings
| Finding | Detail |
|---|---|
| MySQL bottleneck | Was NOT MySQL — 0 slow queries, 6 connections. Bottleneck was app code (5-7 sync SQL trips/event) |
| Batch mode dangers | MyBatis BATCH defers exceptions to `flushStatements()` — try-catch useless at call site |
| ShardingSphere | Wraps exceptions differently — `DuplicateKeyException` not always caught |
| INSERT IGNORE | Best solution for idempotent projection inserts — no try-catch, no dedup cache |
| Raft vs queue | HTTP p(95)=1s is queue wait, not Raft. Raft apply=6-30ms |
| 100 VU saturation | Single account queue = 33 TPS max. Per-account serialization by design |
| lastApplied vs smJournal | 103 diffs = account creations (non-journal ops). Normal |
| Conflation impact | 100:1 reduction for hotspot account during catch-up. Critical for 0 lag |

### Files Changed (cumulative)
| File | Change |
|---|---|
| `ledger-core/.../AccountType.java` | +BANK |
| `ledger-core/.../LedgerStateMachine.java` | +BANK institutional (×2) |
| `ledger-service/.../BalanceQueryService.java` | `effectiveAllowNegative()` |
| `ledger-restful/.../LedgerConfig.java` | Bootstrap refactor + SYSTEM_SEED + BANK_SETTLEMENT |
| `ledger-projection/.../ProjectionConsumer.java` | Full rewrite: async worker, conflation, dedup, INSERT IGNORE |
| `ledger-projection/.../ConflationQueue.java` | NEW — lock-free conflation queue |
| `ledger-projection/.../BalanceUpdate.java` | NEW — balance update record |
| `ledger-projection/.../application.yml` | Kafka consumer tuning |
| `ledger-dao/.../JournalMapper.java` | INSERT IGNORE (journal + journal_line) |
| `ledger-dao/.../ProjectionEventLogMapper.java` | INSERT IGNORE |
| `init.sql` | DECIMAL(34,16) + BANK comment |
| `requirement/NFR-non-functional-requirements.md` | v0.5 dual targets |
| `grafana/.../alert_rules.yml` | +2 projection lag alerts |
| `scripts/k6-posting-stress.js` | USDT 16dp, setup checks, leader refresh, retry, higher seed |
| `docs/orchestrator-status.md` | Full session log |

## [2026-05-31 15:52] Step 0 — ledger-orchestrator (data-recon session)
Status: ✅ PASS
Summary: Completed live data recon against running Docker stack. 204 balances verified cross-node, journal counts match smJournalSeq (69,023), balance sums = 0, zero orphan records. Dispatched ops-sre to formalize `scripts/data-recon.sh`.
Findings: 4 accounts have no account_balance rows (lazy projection, API returns 0 — expected). journal_line sharded across _0.._3; business key cross-ref clean.
Next: ops-sre

## [2026-05-31 16:05] Step 1 — ledger-orchestrator (F-015 Config Abstraction)
Status: ✅ PASS
Summary: Wrote F-015 spec + TC-F015-01~05 (LEDGER-PLATFORM-FULL-REQUIREMENTS.md v0.10→v0.12, TDD-TEST-CASES.md v0.9→v0.10). Implemented: ConfigService interface (ledger-core), SpringConfigService (ledger-restful, reads @Value + yml + env vars). LedgerConfig refactored: zero System.getenv() calls remaining. application.yml extended with outbox.* keys.
Findings: none
Next: docker-compose up → smoke-test.sh → qa-engineer traceability

## [2026-05-31 17:54] Step 2 — ledger-orchestrator (F-015 impl + fix)
Status: ✅ PASS
Summary: Fixed ledger-restful tests (ApplicationContext load). Fixed: (1) @Value key mismatch — raft.server.port → ledger.raft.server-port. (2) RaftNodeManager null in standalone → added @Autowired(required=false) to accountQueueManager parameter. ledger-restful tests: 10/10 PASS.
Findings: none
Next: docker build → smoke tests → commit

## [2026-05-31 18:00] Step 3 — ledger-orchestrator (docker + smoke)
Status: ✅ PASS
Summary: docker-compose build ledger-base + ledger1/2/3 started. All nodes healthy. Smoke tests: 18/18 PASS (balance, idempotency, cross-node consistency, Raft index).
Findings: MySQL projection skipped (projection service not started — expected)
Next: qa-engineer traceability check → final summary

## [2026-06-17] IdempotencyStore Shrink — Full Pipeline

### Commit: (pending)
**Branch:** v3-fix
**Change:** IdempotencyStore only caches COMPLETED entries — drops REJECTED caching.

### Files changed
| File | Change |
|---|---|
| `ledger-core/.../IdempotencyEntry.java` | Simplified: `(requestId, journalId)` — dropped status/errors/errorDetails/completedAt |
| `ledger-core/.../IdempotencyStore.java` | `ConcurrentHashMap<String, String>` — requestId → journalId |
| `ledger-core/.../LedgerStateMachine.java` | Removed 14 `IdempotencyEntry.rejected(...)` stores. Simplified 4 idempotency checks. Updated `persistApply` signature |
| `LedgerStateMachine.SnapshotData` | `Map<String, IdempotencyEntry>` → `Map<String, String>` |
| `requirement/LEDGER-PLATFORM-FULL-REQUIREMENTS.md` | v0.13: F-008 §2.2 + F-013 §2.2-2.5 updated |
| `requirement/TDD-TEST-CASES.md` | v0.17: TC-F008-34~35, TC-F013-02/11 updated; TC-F013-12/13 added |

### Memory impact
~35× reduction: 7× fewer entries (failures dropped) × 5× smaller per entry (6→2 fields).

### Test results (mvn test)
| Module | Tests | Result |
|---|---|---|
| ledger-core | 74 | 0 fail |
| ledger-service | 67 | 0 fail |
| ledger-dao | 0 | PASS |
| ledger-raft-ratis | 2 | 0 fail |
| ledger-restful | 12 | 0 fail |
| ledger-projection | 22 | 0 fail, 1 skip (Docker) |
| ledger-client-sdk | 9 | 0 fail |

### Test-cycle results (docker + k6 + recon)
| Metric | Value |
|---|---|
| Iterations | 21,728 |
| Failed | 0.00% |
| p50 | 3.48ms |
| p95 | 9.66ms |
| Raft lastAppliedIndex | 21,933 / 21,933 / 21,933 ✅ |
| Raft smJournalSeq | 21,830 / 21,830 / 21,830 (diff=0) ✅ |
| Cross-node API balances | 204/204 identical ✅ |
| MySQL balance recon | 204/204 match ✅ |
| Journal count SM vs MySQL | 21,830 = 21,830 (lag=0) ✅ |
| Reconstructed (journal_line) | 202/202 match ✅ |
| **Total checks** | **236 passed, 0 failed, 0 warnings** ✅

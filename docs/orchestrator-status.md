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
- Production: Posting P95≤20ms, P99≤50ms
- Docker/Local: Posting P95≤50ms, P99≤100ms
- Updated k6 thresholds to match

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

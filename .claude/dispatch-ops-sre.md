# Dispatch: ops-sre — Data Reconciliation Script

## Task
Write `scripts/data-recon.sh` — a standalone reconciliation script that validates consistency between Raft state (3-node API) and MySQL projection.

## Context from Current Recon Session

Docker stack is running. All 3 nodes healthy. Leader is node-3 (port 8083).

**What already works / validated today:**
- `smJournalSeq` = 69,023 on all nodes
- `lastAppliedIndex` = 69,126 on all nodes
- MySQL `journal` count = 69,023 (matches smJournalSeq)
- MySQL `journal_line` sharded across `_0`, `_1`, `_2`, `_3` — total 276,088
- `projection_event_log` = 276,088 (matches journal_line count)
- Business `journal_journal_id` cross-ref between `journal` and `journal_line` shards is clean (69,023 ↔ 69,023)
- 204 `account_balance` rows — all match across 3-node API and MySQL numerically
- Balance sums per currency = 0 (BTC and USDT)
- Journal line net sums per currency = 0
- All `projection_event_log` statuses = `APPLIED`
- 4 accounts have no `account_balance` rows but API returns 0 (lazy projection — expected)

## Required Checks (in order)

1. **Stack health** — verify all 3 nodes respond `/health`, identify leader
2. **Raft consistency** — `lastAppliedIndex` and `smJournalSeq` identical across nodes (tolerance ±10 for smJournalSeq)
3. **Journal count recon** — MySQL `journal` count vs leader `smJournalSeq`
4. **Journal line count** — sum counts from `journal_line_0`..`_3`
5. **Event log count** — `projection_event_log` count vs journal_line sum
6. **Journal ID cross-ref** — ensure zero `journal_journal_id` in shards missing from `journal` table, and vice versa
7. **Balance cross-node recon** — for every row in `account_balance`, query all 3 nodes via `/ledger/balances?accountId=...&balanceType=...&currency=...` and compare numerically (use `bc` or Python `Decimal` — NOT string compare due to trailing zeros)
8. **Balance sum recon** — `SELECT SUM(amount) FROM account_balance GROUP BY currency` must be 0 for each currency (within DECIMAL tolerance)
9. **Journal line net sum recon** — for each currency, sum(CREDIT) − sum(DEBIT) across all journal_line shards must be 0
10. **Event status check** — `SELECT status, COUNT(*) FROM projection_event_log GROUP BY status` — flag any non-APPLIED
11. **Orphan sample check** — sample 100 random `projection_event_log.journal_line_id` entries and verify existence in at least one `journal_line_*` shard
12. **Lazy account check** — list accounts in `account` table with no `account_balance` rows; report as INFO (not FAIL) if API returns 0 for them

## Output Format

Use color-coded output:
- `✅ PASS` — green
- `❌ FAIL` — red  
- `⚠️ WARN` — yellow
- `ℹ️ INFO` — blue or plain

End with summary block:
```
========================================
  DATA RECON: X passed, Y failed, Z warnings
  Verdict: PASS / FAIL
========================================
```

Exit code 0 on PASS, 1 on FAIL.

## Script Requirements
- `#!/bin/bash`, `set -e`
- Auto-detect leader via `/health` endpoint (ports 8081, 8082, 8083)
- MySQL via `docker exec ledger-mysql`
- Shard-aware queries for `journal_line_*`
- Numeric comparison using Python `Decimal` or `bc` (do NOT use `=` string compare for decimals)
- Configurable base URL via `--base-url` flag (optional)
- Progress echo per section

## Files to Create/Update
- `scripts/data-recon.sh` (new)
- Update `docs/orchestrator-status.md` after completion

## Gate
- Script must run successfully against current running stack and produce the same clean results observed in this session.

# Loadtest

End-to-end performance harness for jraft-ledger, ported from
`backend-ledger/loadtest` so cross-platform numbers are comparable.

## Layout

```
loadtest/
├── k6/
│   ├── lib/
│   │   ├── data.js              # leader-aware POST, account pool, posting builder
│   │   └── thresholds.js        # SLO bars (write/read, LT_LOOSE_THRESHOLDS=1 to relax)
│   └── scenarios/
│       ├── 03-internal-transfer.js   # 1k tps RFQ-style 2-line posting
│       ├── 05-balance-read.js        # 2k tps read p99 <10ms target
│       ├── 06-idempotency-replay.js  # same requestId × 3 → 1 journal
│       ├── 07-mixed-realistic.js     # 70% post + 30% read, the ship gate
│       ├── 08-burst-5k.js            # ramp 500 → 5000 → 500
│       ├── 09-burst-10k.js           # 400 VUs constant-vus push
│       ├── 10-all-types-2k.js        # 8 business event types at 2k tps
│       └── 14-hot-omnibus.js         # hot-account contention probe
└── scripts/
    ├── seed.sh                  # creates accounts + emits target/loadtest-accounts.json
    ├── run-local.sh             # single-scenario runner
    └── run-all.sh               # short-matrix runner (laptop-friendly)
```

## Key differences from backend-ledger

| backend-ledger                       | jraft-ledger                                                       |
| ------------------------------------ | ------------------------------------------------------------------ |
| `POST /v1/internal-transfers`        | `POST /ledger/postings` (multi-leg, DEBIT + CREDIT lines)          |
| `idempotencyKey`                     | `requestId`                                                        |
| Any node accepts writes              | Writes must hit leader; followers return 503 NOT_LEADER            |
| `networkId` + `assetId`              | `businessEventType` + `currency` at leg level                      |
| Postgres + Flyway schema             | MySQL (projections) + RocksDB (state machine) + Raft               |
| Account roles: master/sub/suspense   | master = COMPANY (auto-topup), sub = CLIENT, suspense = SUSPENSE   |

Leader auto-resolve runs once per VU and re-resolves on 503.

## Prerequisites

```bash
brew install k6
# Docker desktop running, jraft-ledger image built.
```

## Local run

```bash
# 1. bring up cluster
docker compose up -d mysql kafka ledger1 ledger2 ledger3

# 2. wait for leader
until curl -fs http://localhost:8081/health | jq -e '.role == "LEADER"' >/dev/null; do sleep 2; done

# 3. seed accounts
loadtest/scripts/seed.sh                    # ACCOUNT_COUNT=1000 by default

# 4. one scenario
loadtest/scripts/run-local.sh 03-internal-transfer

# 5. full matrix
loadtest/scripts/run-all.sh
```

Reports drop into `loadtest/reports/<timestamp>-<scenario>/`:
- `stdout.log`   — full k6 output
- `summary.json` — machine-readable summary

## SLO thresholds

Defined in `k6/lib/thresholds.js`.

| Scenario             | p50    | p95    | p99    | p99.9   |
| -------------------- | ------ | ------ | ------ | ------- |
| Write path (03 / 07) | <10ms  | <30ms  | <50ms  | <200ms  |
| Read path (05)       | <5ms   | —      | <10ms  | <50ms   |
| Burst 5k (08)        | —      | —      | <100ms | <500ms  |

jraft-ledger's CLAUDE.md §2.10 target (Posting P95 ≤ 3 ms) is stricter than
the bars above — these are the backend-ledger ship gate, kept here for
apples-to-apples comparison.

Set `LT_LOOSE_THRESHOLDS=1` to relax `http_req_failed` and `checks` rates
during early experiments where 503 NOT_LEADER storms would otherwise paint
every run red.

## Known issue (prior run 2026-05-27)

`docs/LOAD-TEST-RESULTS-2026-05-27.md` records a previous run on the same
cluster. Sustained writes (even at 1 VU serial) triggered Raft leadership
churn — see that file for root-cause notes before chasing tail latency here.

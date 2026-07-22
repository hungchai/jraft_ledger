# Dispatch: ledger-requirements

**From**: ledger-orchestrator
**Task**: Update requirements with Kafka message samples + MySQL schema
**Agent**: ledger-requirements (see `.claude/agents/ledger-requirements.md`)
**Dispatched**: 2026-05-31T02:30:31Z

## Context

## Task
Update LEDGER-PLATFORM-FULL-REQUIREMENTS.md with:
1. Kafka message samples for `ledger.balance.change.v1` and `ledger.account.v1` topics
2. MySQL View Layer schema (tables, columns, types, indexes)

## Context — DB Schema (from init.sql)
5 tables in `ledger_view` database:

**journal** — Journal header. Append-only.
- id BIGINT AUTO_INCREMENT PK
- journal_id VARCHAR(64) UNIQUE — business key (JNL-XXXX)
- journal_type VARCHAR(32) — NORMAL | REVERSAL | MANUAL_ADJUSTMENT
- request_id VARCHAR(64) — idempotency key (UUID v7)
- business_event_type VARCHAR(64) — RFQ_SETTLEMENT | WITHDRAWAL | FEE
- business_event_ref VARCHAR(128)
- value_date DATE
- status VARCHAR(16) — CONFIRMED | REVERSED
- cross_period BOOLEAN
- created_at, updated_at TIMESTAMP(6)

**account** — Account metadata.
- id BIGINT AUTO_INCREMENT PK
- account_id VARCHAR(64) UNIQUE
- account_type VARCHAR(16) — CLIENT | COMPANY | NOSTRO | SUSPENSE | CONTROL | BANK
- display_name, owner_id
- status VARCHAR(16) — ACTIVE | FROZEN | CLOSED
- created_at, updated_at TIMESTAMP(6)

**account_balance** — Materialized balance view. One row per (account_id, balance_type, currency).
- id BIGINT AUTO_INCREMENT PK
- account_id BIGINT (surrogate → account.id, no FK)
- account_account_id VARCHAR(64) (denormalized business key)
- balance_type VARCHAR(64)
- currency VARCHAR(8)
- amount DECIMAL(34,16) — CURRENT position
- frozen_amount DECIMAL(34,16) — FROZEN position
- locked_amount DECIMAL(34,16) — LOCKED position
- account_seq BIGINT — monotonic seq guard
- last_journal_id VARCHAR(64)
- created_at, updated_at TIMESTAMP(6)
- UK on (account_account_id, balance_type, currency)

**projection_event_log** — Idempotency guard.
- id BIGINT AUTO_INCREMENT PK
- account_account_id, balance_type, currency
- account_seq BIGINT
- journal_line_id VARCHAR(72)
- journal_journal_id VARCHAR(64)
- event_id VARCHAR(64) — Kafka event UUID
- status VARCHAR(16) — APPLIED | SKIPPED_DUPLICATE | SKIPPED_STALE
- processed_at TIMESTAMP(6)
- UK on (account_account_id, balance_type, currency, account_seq)

**journal_line** — Journal line items. Append-only. Sharded by account_id.
- id BIGINT AUTO_INCREMENT PK
- journal_id BIGINT (surrogate → journal.id)
- account_id BIGINT (surrogate → account.id)
- account_balance_id BIGINT (surrogate → account_balance.id)
- journal_line_id VARCHAR(72) UNIQUE
- journal_journal_id VARCHAR(64) (denormalized)
- account_account_id VARCHAR(64) (denormalized)
- leg_id VARCHAR(64)
- balance_type VARCHAR(64)
- position VARCHAR(16) — CURRENT | LOCKED | FROZEN
- currency VARCHAR(8)
- entry_type VARCHAR(8) — DEBIT | CREDIT
- amount DECIMAL(34,16)
- balance_before DECIMAL(34,16)
- balance_after DECIMAL(34,16)
- config_version INT
- created_at, updated_at TIMESTAMP(6)
- Sharded: journal_line_0, journal_line_1, journal_line_2, journal_line_3 (4 shards)
- No FK constraints — integrity from RocksDB State Machine

## Context — Kafka Messages

**Topic: `ledger.account.v1`**
```json
{
  "accountId": "STRESS-HOT-CO-001",
  "accountType": "COMPANY",
  "displayName": "Hotspot Co",
  "ownerId": "CO-HOTSPOT",
  "status": "ACTIVE",
  "createdAt": [2026, 5, 30, 13, 30, 0, 123456000]
}
```

**Topic: `ledger.balance.change.v1`**
```json
{
  "eventId": "19e7827f731a12e2502069a44e6",
  "journalId": "JNL-0001",
  "journalLineId": "JNL-0001-01",
  "requestId": "k6-2-0-1780132341011",
  "commandType": "POSTING",
  "accountId": "STRESS-HOT-CO-001",
  "balanceType": "AVAILABLE_BALANCE",
  "position": "CURRENT",
  "currency": "USDT",
  "entryType": "CREDIT",
  "amount": 5000.00000000,
  "preBalance": 0,
  "postBalance": 5000.00000000,
  "accountSeq": 1,
  "businessEventRef": "SEED-BTC",
  "valueDate": [2026, 5, 27],
  "configVersion": 1
}
```

## Notes
- DECIMAL(34,16) supports USDT 16dp + BTC 8dp
- INSERT IGNORE used for idempotent projection inserts
- Account balance upsert uses accountSeq guard: `IF(seq >= stored, new_value, old_value)`
- journal_line sharded via ShardingSphere-JDBC by account_id hash
</your-response>

Let me just write the dispatch directly.<｜end▁of▁thinking｜>

<｜｜DSML｜｜tool_calls>
<｜｜DSML｜｜invoke name="Bash">
<｜｜DSML｜｜parameter name="command" string="true">./scripts/dispatch.sh ledger-requirements \
  "Update requirements doc with Kafka message samples + MySQL schema" \
  --body /tmp/dispatch-req-body.md
## Status

Pending — awaiting agent pickup.

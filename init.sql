-- ============================================================
-- MySQL View Layer — Projection Schema v2
--
-- Naming convention:
--   {table}_id            → surrogate PK of {table}
--   {table}_{column}      → denormalized business key
--
-- Principles:
--   - No FOREIGN KEY constraints. Integrity guaranteed by RocksDB
--     State Machine (source of truth).
--   - Surrogate BIGINT PKs on all tables for efficient JOIN targets.
--   - Business keys denormalized into child tables — 95% of read
--     queries hit business key indexes, no JOIN needed.
--   - account_balance has accountSeq guard: UPDATE only when
--     incoming seq >= stored seq.
--   - projection_event_log for idempotency: UK on (account_id,
--     balance_type, currency, account_seq) rejects exact duplicates.
-- ============================================================

-- ============================================================
-- journal — Journal header. Append-only.
-- ============================================================
CREATE TABLE IF NOT EXISTS journal (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY  COMMENT 'Surrogate primary key. journal_id in child tables references this.',
    journal_id          VARCHAR(64) NOT NULL UNIQUE        COMMENT 'Business-facing journal identifier, e.g. JNL-20260525-00001',
    journal_type        VARCHAR(32) NOT NULL               COMMENT 'Journal classification: NORMAL | REVERSAL | MANUAL_ADJUSTMENT',
    request_id          VARCHAR(64) NOT NULL               COMMENT 'Original request idempotency key (UUID v7)',
    business_event_type VARCHAR(64)                        COMMENT 'Business event type: RFQ_SETTLEMENT | WITHDRAWAL | FEE',
    business_event_ref  VARCHAR(128)                       COMMENT 'Upstream business event reference ID',
    value_date          DATE NOT NULL                      COMMENT 'Accounting effective date',
    status              VARCHAR(16) NOT NULL               COMMENT 'Journal lifecycle status: CONFIRMED | REVERSED',
    cross_period        BOOLEAN DEFAULT FALSE              COMMENT 'Whether reversal crosses an accounting period boundary',
    created_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Record creation timestamp (UTC, microsecond precision)',
    updated_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'Last update timestamp (UTC, microsecond precision)',
    INDEX idx_request_id (request_id),
    INDEX idx_business_event_ref (business_event_ref),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Journal header table. Append-only — never UPDATE journal data columns. Source: RocksDB → Kafka → ProjectionConsumer.';

-- ============================================================
-- account — Account metadata.
-- ============================================================
CREATE TABLE IF NOT EXISTS account (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY  COMMENT 'Surrogate primary key. account_id in child tables references this.',
    account_id       VARCHAR(64) NOT NULL UNIQUE        COMMENT 'Business-facing account identifier',
    account_type     VARCHAR(16) NOT NULL               COMMENT 'Account type: CLIENT | COMPANY | NOSTRO | SUSPENSE | CONTROL | BANK',
    display_name     VARCHAR(128)                       COMMENT 'Human-readable display name for the account',
    owner_id         VARCHAR(64)                        COMMENT 'Owning entity / legal person identifier',
    status           VARCHAR(16) NOT NULL               COMMENT 'Account lifecycle status: ACTIVE | FROZEN | CLOSED',
    created_at       TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Record creation timestamp (UTC, microsecond precision)',
    updated_at       TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'Last update timestamp (UTC, microsecond precision)',
    INDEX idx_owner_id (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Account metadata table. Source: ledger.account.v1 Kafka topic.';

-- ============================================================
-- account_balance — Materialized balance view.
-- One row per (account_account_id, balance_type, currency).
-- Position is logical: amount=CURRENT, frozen_amount=FROZEN, locked_amount=LOCKED.
-- ============================================================
CREATE TABLE IF NOT EXISTS account_balance (
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY  COMMENT 'Surrogate primary key. account_balance_id in journal_line references this.',
    account_id             BIGINT NOT NULL                    COMMENT 'References account.id (surrogate). No FK constraint — integrity from RocksDB.',
    account_account_id     VARCHAR(64) NOT NULL               COMMENT 'Denormalized: account.account_id (business key). Direct query target.',
    balance_type           VARCHAR(64) NOT NULL               COMMENT 'Balance type code from F-001 registry',
    currency               VARCHAR(8) NOT NULL                COMMENT 'ISO 4217 currency code',
    amount                 DECIMAL(34,16) NOT NULL DEFAULT 0.0000000000000000 COMMENT 'CURRENT position balance in account currency',
    frozen_amount          DECIMAL(34,16) NOT NULL DEFAULT 0.0000000000000000 COMMENT 'FROZEN position balance (compliance / legal hold)',
    locked_amount          DECIMAL(34,16) NOT NULL DEFAULT 0.0000000000000000 COMMENT 'LOCKED position balance (pending Maker-Checker approval)',
    account_seq            BIGINT NOT NULL DEFAULT 0          COMMENT 'Latest applied monotonic sequence number. Guard: update only when incoming seq >= stored seq prevents stale/out-of-order overwrite.',
    last_journal_id        VARCHAR(64)                        COMMENT 'Most recent journal_id that caused a balance change on this row',
    created_at             TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Record creation timestamp (UTC, microsecond precision)',
    updated_at             TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'Last update timestamp (UTC, microsecond precision)',
    UNIQUE KEY uk_account_balance (account_account_id, balance_type, currency),
    INDEX idx_account_id (account_id),
    INDEX idx_account_account_id (account_account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Materialized balance view. Projection target. No FK — integrity from RocksDB State Machine. Surrogate id for journal_line FK join.';

-- ============================================================
-- projection_event_log — Idempotency guard for the projection.
-- One row per balance state transition. UK rejects exact duplicate
-- events. Application-level check rejects stale (lower account_seq).
-- ============================================================
CREATE TABLE IF NOT EXISTS projection_event_log (
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY  COMMENT 'Internal surrogate key',
    account_account_id     VARCHAR(64) NOT NULL               COMMENT 'Account business identifier',
    balance_type           VARCHAR(64) NOT NULL               COMMENT 'Balance type code from F-001 registry',
    currency               VARCHAR(8) NOT NULL                COMMENT 'ISO 4217 currency code',
    account_seq            BIGINT NOT NULL                    COMMENT 'Monotonic sequence number of this balance state transition',
    journal_line_id        VARCHAR(72) NOT NULL               COMMENT 'Journal line business identifier that triggered this event',
    journal_journal_id     VARCHAR(64) NOT NULL               COMMENT 'Journal business identifier',
    event_id               VARCHAR(64) NOT NULL               COMMENT 'Kafka event UUID for cross-reference tracing',
    status                 VARCHAR(16) NOT NULL DEFAULT 'APPLIED' COMMENT 'Processing outcome: APPLIED | SKIPPED_DUPLICATE | SKIPPED_STALE',
    processed_at           TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Timestamp when event was processed (UTC, microsecond precision)',
    UNIQUE KEY uk_event_seq (account_account_id, balance_type, currency, account_seq),
    INDEX idx_journal_line_id (journal_line_id),
    INDEX idx_processed_at (processed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Projection idempotency event log. One row per (account_account_id, balance_type, currency, account_seq). UK rejects exact Kafka duplicates. Application rejects stale sequences. Sharded by account_account_id hash.';

-- ============================================================
-- Sharding physical tables for projection_event_log (4 shards,
-- aligned with journal_line). Sharded by account_account_id hash
-- via ShardingSphere-JDBC. LIKE copies all columns, indexes and
-- the uk_event_seq unique key.
-- ============================================================
CREATE TABLE IF NOT EXISTS projection_event_log_0 LIKE projection_event_log;
CREATE TABLE IF NOT EXISTS projection_event_log_1 LIKE projection_event_log;
CREATE TABLE IF NOT EXISTS projection_event_log_2 LIKE projection_event_log;
CREATE TABLE IF NOT EXISTS projection_event_log_3 LIKE projection_event_log;

-- ============================================================
-- journal_line — Journal line items. Append-only.
-- FK hub with three join targets + denormalized business keys.
-- Sharded by account_account_id hash via ShardingSphere-JDBC.
-- ============================================================
CREATE TABLE IF NOT EXISTS journal_line (
    id                           BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'Internal surrogate key',

    -- Join target columns ({table}_id → {table}.id, BIGINT surrogate, no FK constraint)
    journal_id                   BIGINT NOT NULL              COMMENT 'References journal.id (surrogate PK). No FK constraint.',
    account_id                   BIGINT NOT NULL              COMMENT 'References account.id (surrogate PK). No FK constraint.',
    account_balance_id           BIGINT NOT NULL              COMMENT 'References account_balance.id (surrogate PK). No FK constraint.',

    -- Business key columns ({table}_{column}, VARCHAR, denormalized — no JOIN needed for 95% of read queries)
    journal_line_id              VARCHAR(72) NOT NULL UNIQUE  COMMENT 'Business-facing journal line identifier',
    journal_journal_id           VARCHAR(64) NOT NULL         COMMENT 'Denormalized: journal.journal_id (business key)',
    account_account_id           VARCHAR(64) NOT NULL         COMMENT 'Denormalized: account.account_id (business key)',

    leg_id                       VARCHAR(64)                  COMMENT 'Leg identifier within a multi-leg posting',
    balance_type                 VARCHAR(64) NOT NULL         COMMENT 'Balance type code from F-001 registry',
    position                     VARCHAR(16) NOT NULL DEFAULT 'CURRENT' COMMENT 'Balance position affected: CURRENT | LOCKED | FROZEN',
    currency                     VARCHAR(8) NOT NULL          COMMENT 'ISO 4217 currency code',
    entry_type                   VARCHAR(8) NOT NULL          COMMENT 'Double-entry direction: DEBIT | CREDIT',
    amount                       DECIMAL(34,16) NOT NULL       COMMENT 'Transaction amount in the currency unit (must be > 0)',
    balance_before               DECIMAL(34,16) NOT NULL       COMMENT 'Account balance before this journal line was applied',
    balance_after                DECIMAL(34,16) NOT NULL       COMMENT 'Account balance after this journal line was applied',
    config_version               INT NOT NULL                 COMMENT 'Balance type registry config version active at posting time',
    created_at                   TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Record creation timestamp (UTC, microsecond precision)',
    updated_at                   TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'Last update timestamp (UTC, microsecond precision)',

    INDEX idx_journal_id (journal_id),
    INDEX idx_account_id (account_id),
    INDEX idx_account_balance_id (account_balance_id),
    INDEX idx_journal_journal_id (journal_journal_id),
    INDEX idx_account_account_id (account_account_id),
    INDEX idx_account_balance (account_account_id, balance_type, currency)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Journal line items. Append-only — never UPDATE data columns. All FK references are logical only (no constraint). Sharded by account_account_id hash.';

-- ============================================================
-- Sharding physical tables for journal_line (4 shards by default)
-- ============================================================
CREATE TABLE IF NOT EXISTS journal_line_0 LIKE journal_line;
CREATE TABLE IF NOT EXISTS journal_line_1 LIKE journal_line;
CREATE TABLE IF NOT EXISTS journal_line_2 LIKE journal_line;
CREATE TABLE IF NOT EXISTS journal_line_3 LIKE journal_line;

-- ============================================================
-- balance_type_registry — Balance type configuration
-- ============================================================
CREATE TABLE IF NOT EXISTS balance_type_registry (
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY  COMMENT 'Internal surrogate key',
    type_code              VARCHAR(64) NOT NULL UNIQUE        COMMENT 'Globally unique balance type code, e.g. AVAILABLE_BALANCE. Immutable after creation.',
    display_name           JSON NOT NULL                      COMMENT 'i18n display name map, e.g. {"en": "Available Balance", "zh-HK": "可用餘額"}',
    description            TEXT NOT NULL                      COMMENT 'Business description for documentation and audit reports',
    category               VARCHAR(32) NOT NULL               COMMENT 'Balance category: ACTUAL | PROJECTED | RESERVED | SHADOW',
    status                 VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'Registry lifecycle status: ACTIVE | INACTIVE | DEPRECATED',
    sign_convention        VARCHAR(32) NOT NULL               COMMENT 'Sign convention: NORMAL_CREDIT (credit increases balance) | NORMAL_DEBIT (debit increases balance)',
    allow_negative         BOOLEAN NOT NULL DEFAULT FALSE     COMMENT 'Whether negative balance is permitted (e.g. true for overdraft accounts)',
    negative_semantics     VARCHAR(32)                        COMMENT 'Semantics of negative balance when allow_negative=true: OVERDRAFT | SHORT_POSITION | PRE_AUTHORIZED | CREDIT_UTILIZATION',
    zero_floor_enforce     BOOLEAN NOT NULL DEFAULT TRUE      COMMENT 'Whether to enforce balance >= 0 as a hard floor',
    currency_scope         VARCHAR(32) NOT NULL               COMMENT 'Currency scope: SINGLE_CCY | MULTI_CCY | BASE_CCY_ONLY',
    config_version         INTEGER NOT NULL DEFAULT 1         COMMENT 'Monotonic config version, incremented on each modification',
    created_by             VARCHAR(64) NOT NULL               COMMENT 'Operator ID that created this config (passed from upstream domain)',
    created_at             TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Config creation timestamp (UTC, microsecond precision)',
    updated_at             TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'Last update timestamp (UTC, microsecond precision)',
    last_modified_by       VARCHAR(64)                        COMMENT 'Operator ID that last modified this config',
    last_modified_at       TIMESTAMP(6)                       COMMENT 'Timestamp of last modification (UTC, microsecond precision)',
    change_reason          TEXT NOT NULL                      COMMENT 'Free-text reason for the change, required on every modification'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Balance type registry configuration. Source: F-001 Admin API → MySQL. Config changes hot-reloaded to in-memory cache.';

-- ============================================================
-- PostgreSQL View Layer — Projection Schema v2 (migrated from MySQL)
--
-- Naming convention:
--   {table}_id            → surrogate PK of {table}
--   {table}_{column}      → denormalized business key
--
-- Principles:
--   - No FOREIGN KEY constraints. Integrity guaranteed by RocksDB
--     State Machine (source of truth).
--   - Surrogate BIGINT PKs (BIGSERIAL) on all tables for efficient JOIN targets.
--   - Business keys denormalized into child tables — 95% of read
--     queries hit business key indexes, no JOIN needed.
--   - account_balance has accountSeq guard: UPDATE only when
--     incoming seq >= stored seq.
--   - projection_event_log for idempotency: UK on (account_id,
--     balance_type, currency, account_seq) rejects exact duplicates.
--
-- PostgreSQL notes vs the MySQL original:
--   - AUTO_INCREMENT → BIGSERIAL; inline COMMENT → COMMENT ON COLUMN;
--     inline non-unique INDEX → CREATE INDEX; UNIQUE KEY → UNIQUE constraint;
--     DECIMAL → NUMERIC; JSON → JSONB; CREATE TABLE .. LIKE → LIKE INCLUDING ALL.
--   - No "ON UPDATE CURRENT_TIMESTAMP": updated_at is set explicitly by the
--     projection writers (ON CONFLICT DO UPDATE SET ... updated_at = now()).
-- ============================================================

-- ============================================================
-- journal — Journal header. Append-only.
-- ============================================================
CREATE TABLE IF NOT EXISTS journal (
    id                  BIGSERIAL PRIMARY KEY,
    journal_id          VARCHAR(64) NOT NULL UNIQUE,
    journal_type        VARCHAR(32) NOT NULL,
    request_id          VARCHAR(64) NOT NULL,
    business_event_type VARCHAR(64),
    business_event_ref  VARCHAR(128),
    value_date          DATE NOT NULL,
    status              VARCHAR(16) NOT NULL,
    cross_period        BOOLEAN DEFAULT FALSE,
    created_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE  journal IS 'Journal header table. Append-only — never UPDATE journal data columns. Source: RocksDB → Kafka → ProjectionConsumer.';
COMMENT ON COLUMN journal.id IS 'Surrogate primary key. journal_id in child tables references this.';
COMMENT ON COLUMN journal.journal_id IS 'Business-facing journal identifier, e.g. JNL-20260525-00001';
COMMENT ON COLUMN journal.journal_type IS 'Journal classification: NORMAL | REVERSAL | MANUAL_ADJUSTMENT';
COMMENT ON COLUMN journal.request_id IS 'Original request idempotency key (UUID v7)';
COMMENT ON COLUMN journal.business_event_type IS 'Business event type: RFQ_SETTLEMENT | WITHDRAWAL | FEE';
COMMENT ON COLUMN journal.business_event_ref IS 'Upstream business event reference ID';
COMMENT ON COLUMN journal.value_date IS 'Accounting effective date';
COMMENT ON COLUMN journal.status IS 'Journal lifecycle status: CONFIRMED | REVERSED';
COMMENT ON COLUMN journal.cross_period IS 'Whether reversal crosses an accounting period boundary (true/false)';
COMMENT ON COLUMN journal.created_at IS 'Record creation timestamp (UTC, microsecond precision)';
COMMENT ON COLUMN journal.updated_at IS 'Last update timestamp (UTC, microsecond precision; set explicitly by writer)';

-- ============================================================
-- account — Account metadata.
-- ============================================================
CREATE TABLE IF NOT EXISTS account (
    id               BIGSERIAL PRIMARY KEY,
    account_id       VARCHAR(64) NOT NULL UNIQUE,
    account_type     VARCHAR(16) NOT NULL,
    display_name     VARCHAR(128),
    owner_id         VARCHAR(64),
    status           VARCHAR(16) NOT NULL,
    created_at       TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE  account IS 'Account metadata table. Source: ledger.account.v1 Kafka topic.';
COMMENT ON COLUMN account.id IS 'Surrogate primary key. account_id in child tables references this.';
COMMENT ON COLUMN account.account_id IS 'Business-facing account identifier';
COMMENT ON COLUMN account.account_type IS 'Account type: CLIENT | COMPANY | NOSTRO | SUSPENSE | CONTROL | BANK';
COMMENT ON COLUMN account.display_name IS 'Human-readable display name for the account';
COMMENT ON COLUMN account.owner_id IS 'Owning entity / legal person identifier';
COMMENT ON COLUMN account.status IS 'Account lifecycle status: ACTIVE | FROZEN | CLOSED';
COMMENT ON COLUMN account.created_at IS 'Record creation timestamp (UTC, microsecond precision)';
COMMENT ON COLUMN account.updated_at IS 'Last update timestamp (UTC, microsecond precision; set explicitly by writer)';

-- ============================================================
-- account_balance — Materialized balance view. Projection target.
-- ============================================================
CREATE TABLE IF NOT EXISTS account_balance (
    id                     BIGSERIAL PRIMARY KEY,
    account_id             BIGINT NOT NULL,
    account_account_id     VARCHAR(64) NOT NULL,
    balance_type           VARCHAR(64) NOT NULL,
    currency               VARCHAR(8) NOT NULL,
    amount                 NUMERIC(34,16) NOT NULL DEFAULT 0,
    frozen_amount          NUMERIC(34,16) NOT NULL DEFAULT 0,
    locked_amount          NUMERIC(34,16) NOT NULL DEFAULT 0,
    account_seq            BIGINT NOT NULL DEFAULT 0,
    last_journal_id        VARCHAR(64),
    created_at             TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_account_balance UNIQUE (account_account_id, balance_type, currency)
);
COMMENT ON TABLE  account_balance IS 'Materialized balance view. Projection target. No FK — integrity from RocksDB State Machine. Surrogate id for journal_line join.';
COMMENT ON COLUMN account_balance.id IS 'Surrogate primary key. account_balance_id in journal_line references this.';
COMMENT ON COLUMN account_balance.account_id IS 'References account.id (surrogate). No FK constraint — integrity from RocksDB.';
COMMENT ON COLUMN account_balance.account_account_id IS 'Denormalized: account.account_id (business key). Direct query target.';
COMMENT ON COLUMN account_balance.balance_type IS 'Balance type code from F-001 registry';
COMMENT ON COLUMN account_balance.currency IS 'ISO 4217 currency code';
COMMENT ON COLUMN account_balance.amount IS 'CURRENT position balance in account currency';
COMMENT ON COLUMN account_balance.frozen_amount IS 'FROZEN position balance (compliance / legal hold)';
COMMENT ON COLUMN account_balance.locked_amount IS 'LOCKED position balance (pending Maker-Checker approval)';
COMMENT ON COLUMN account_balance.account_seq IS 'Latest applied monotonic sequence number. Guard: update only when incoming seq >= stored seq prevents stale/out-of-order overwrite.';
COMMENT ON COLUMN account_balance.last_journal_id IS 'Most recent journal_id that caused a balance change on this row';
COMMENT ON COLUMN account_balance.created_at IS 'Record creation timestamp (UTC, microsecond precision)';
COMMENT ON COLUMN account_balance.updated_at IS 'Last update timestamp (UTC, microsecond precision; set explicitly by writer)';

-- ============================================================
-- projection_event_log — idempotency log (logical table + 4 shards).
-- ============================================================
CREATE TABLE IF NOT EXISTS projection_event_log (
    id                     BIGSERIAL PRIMARY KEY,
    account_account_id     VARCHAR(64) NOT NULL,
    balance_type           VARCHAR(64) NOT NULL,
    currency               VARCHAR(8) NOT NULL,
    account_seq            BIGINT NOT NULL,
    journal_line_id        VARCHAR(72) NOT NULL,
    journal_journal_id     VARCHAR(64) NOT NULL,
    event_id               VARCHAR(64) NOT NULL,
    status                 VARCHAR(16) NOT NULL DEFAULT 'APPLIED',
    processed_at           TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_event_seq UNIQUE (account_account_id, balance_type, currency, account_seq)
);
COMMENT ON TABLE  projection_event_log IS 'Projection idempotency event log. One row per (account_account_id, balance_type, currency, account_seq). UK rejects exact Kafka duplicates. Application rejects stale sequences. Sharded by account_account_id hash.';
COMMENT ON COLUMN projection_event_log.id IS 'Internal surrogate key';
COMMENT ON COLUMN projection_event_log.account_account_id IS 'Account business identifier';
COMMENT ON COLUMN projection_event_log.balance_type IS 'Balance type code from F-001 registry';
COMMENT ON COLUMN projection_event_log.currency IS 'ISO 4217 currency code';
COMMENT ON COLUMN projection_event_log.account_seq IS 'Monotonic sequence number of this balance state transition';
COMMENT ON COLUMN projection_event_log.journal_line_id IS 'Journal line business identifier that triggered this event';
COMMENT ON COLUMN projection_event_log.journal_journal_id IS 'Journal business identifier';
COMMENT ON COLUMN projection_event_log.event_id IS 'Kafka event UUID for cross-reference tracing';
COMMENT ON COLUMN projection_event_log.status IS 'Processing outcome: APPLIED | SKIPPED_DUPLICATE | SKIPPED_STALE';
COMMENT ON COLUMN projection_event_log.processed_at IS 'Timestamp when event was processed (UTC, microsecond precision)';

-- Sharding physical tables for projection_event_log (4 shards, aligned with journal_line).
-- LIKE INCLUDING ALL copies columns, defaults, the uk_event_seq unique constraint, and comments.
CREATE TABLE IF NOT EXISTS projection_event_log_0 (LIKE projection_event_log INCLUDING ALL);
CREATE TABLE IF NOT EXISTS projection_event_log_1 (LIKE projection_event_log INCLUDING ALL);
CREATE TABLE IF NOT EXISTS projection_event_log_2 (LIKE projection_event_log INCLUDING ALL);
CREATE TABLE IF NOT EXISTS projection_event_log_3 (LIKE projection_event_log INCLUDING ALL);

-- ============================================================
-- journal_line — Journal line items. Append-only (logical table + 4 shards).
-- ============================================================
CREATE TABLE IF NOT EXISTS journal_line (
    id                           BIGSERIAL PRIMARY KEY,
    journal_id                   BIGINT NOT NULL,
    account_id                   BIGINT NOT NULL,
    account_balance_id           BIGINT NOT NULL,
    journal_line_id              VARCHAR(72) NOT NULL UNIQUE,
    journal_journal_id           VARCHAR(64) NOT NULL,
    account_account_id           VARCHAR(64) NOT NULL,
    leg_id                       VARCHAR(64),
    balance_type                 VARCHAR(64) NOT NULL,
    position                     VARCHAR(16) NOT NULL DEFAULT 'CURRENT',
    currency                     VARCHAR(8) NOT NULL,
    entry_type                   VARCHAR(8) NOT NULL,
    amount                       NUMERIC(34,16) NOT NULL,
    balance_before               NUMERIC(34,16) NOT NULL,
    balance_after                NUMERIC(34,16) NOT NULL,
    config_version               INTEGER NOT NULL,
    created_at                   TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                   TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE  journal_line IS 'Journal line items. Append-only — never UPDATE data columns. All FK references are logical only (no constraint). Sharded by account_account_id hash.';
COMMENT ON COLUMN journal_line.id IS 'Internal surrogate key';
COMMENT ON COLUMN journal_line.journal_id IS 'References journal.id (surrogate PK). No FK constraint.';
COMMENT ON COLUMN journal_line.account_id IS 'References account.id (surrogate PK). No FK constraint.';
COMMENT ON COLUMN journal_line.account_balance_id IS 'References account_balance.id (surrogate PK). No FK constraint.';
COMMENT ON COLUMN journal_line.journal_line_id IS 'Business-facing journal line identifier';
COMMENT ON COLUMN journal_line.journal_journal_id IS 'Denormalized: journal.journal_id (business key)';
COMMENT ON COLUMN journal_line.account_account_id IS 'Denormalized: account.account_id (business key)';
COMMENT ON COLUMN journal_line.leg_id IS 'Leg identifier within a multi-leg posting';
COMMENT ON COLUMN journal_line.balance_type IS 'Balance type code from F-001 registry';
COMMENT ON COLUMN journal_line.position IS 'Balance position affected: CURRENT | LOCKED | FROZEN';
COMMENT ON COLUMN journal_line.currency IS 'ISO 4217 currency code';
COMMENT ON COLUMN journal_line.entry_type IS 'Double-entry direction: DEBIT | CREDIT';
COMMENT ON COLUMN journal_line.amount IS 'Transaction amount in the currency unit (must be > 0)';
COMMENT ON COLUMN journal_line.balance_before IS 'Account balance before this journal line was applied';
COMMENT ON COLUMN journal_line.balance_after IS 'Account balance after this journal line was applied';
COMMENT ON COLUMN journal_line.config_version IS 'Balance type registry config version active at posting time';
COMMENT ON COLUMN journal_line.created_at IS 'Record creation timestamp (UTC, microsecond precision)';
COMMENT ON COLUMN journal_line.updated_at IS 'Last update timestamp (UTC, microsecond precision; set explicitly by writer)';

-- Non-unique secondary indexes (MySQL inline INDEX → PG CREATE INDEX) on the logical table.
CREATE INDEX IF NOT EXISTS idx_journal_line_journal_id          ON journal_line (journal_id);
CREATE INDEX IF NOT EXISTS idx_journal_line_account_id          ON journal_line (account_id);
CREATE INDEX IF NOT EXISTS idx_journal_line_account_balance_id  ON journal_line (account_balance_id);
CREATE INDEX IF NOT EXISTS idx_journal_line_journal_journal_id  ON journal_line (journal_journal_id);
CREATE INDEX IF NOT EXISTS idx_journal_line_account_account_id  ON journal_line (account_account_id);
CREATE INDEX IF NOT EXISTS idx_journal_line_acct_bt_ccy         ON journal_line (account_account_id, balance_type, currency);

-- Sharding physical tables for journal_line (4 shards). LIKE INCLUDING ALL copies the indexes too.
CREATE TABLE IF NOT EXISTS journal_line_0 (LIKE journal_line INCLUDING ALL);
CREATE TABLE IF NOT EXISTS journal_line_1 (LIKE journal_line INCLUDING ALL);
CREATE TABLE IF NOT EXISTS journal_line_2 (LIKE journal_line INCLUDING ALL);
CREATE TABLE IF NOT EXISTS journal_line_3 (LIKE journal_line INCLUDING ALL);

-- ============================================================
-- balance_type_registry — Balance type configuration.
-- ============================================================
CREATE TABLE IF NOT EXISTS balance_type_registry (
    id                     BIGSERIAL PRIMARY KEY,
    type_code              VARCHAR(64) NOT NULL UNIQUE,
    display_name           JSONB NOT NULL,
    description            TEXT NOT NULL,
    category               VARCHAR(32) NOT NULL,
    status                 VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    sign_convention        VARCHAR(32) NOT NULL,
    allow_negative         BOOLEAN NOT NULL DEFAULT FALSE,
    negative_semantics     VARCHAR(32),
    zero_floor_enforce     BOOLEAN NOT NULL DEFAULT TRUE,
    currency_scope         VARCHAR(32) NOT NULL,
    config_version         INTEGER NOT NULL DEFAULT 1,
    created_by             VARCHAR(64) NOT NULL,
    created_at             TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_modified_by       VARCHAR(64),
    last_modified_at       TIMESTAMP(6),
    change_reason          TEXT NOT NULL
);
COMMENT ON TABLE  balance_type_registry IS 'Balance type registry configuration. Source: F-001 Admin API → DB. Config changes hot-reloaded to in-memory cache.';
COMMENT ON COLUMN balance_type_registry.id IS 'Internal surrogate key';
COMMENT ON COLUMN balance_type_registry.type_code IS 'Globally unique balance type code, e.g. AVAILABLE_BALANCE. Immutable after creation.';
COMMENT ON COLUMN balance_type_registry.display_name IS 'i18n display name map (JSONB), e.g. {"en": "Available Balance", "zh-HK": "可用餘額"}';
COMMENT ON COLUMN balance_type_registry.description IS 'Business description for documentation and audit reports';
COMMENT ON COLUMN balance_type_registry.category IS 'Balance category: ACTUAL | PROJECTED | RESERVED | SHADOW';
COMMENT ON COLUMN balance_type_registry.status IS 'Registry lifecycle status: ACTIVE | INACTIVE | DEPRECATED';
COMMENT ON COLUMN balance_type_registry.sign_convention IS 'Sign convention: NORMAL_CREDIT (credit increases balance) | NORMAL_DEBIT (debit increases balance)';
COMMENT ON COLUMN balance_type_registry.allow_negative IS 'Whether negative balance is permitted (e.g. true for overdraft accounts)';
COMMENT ON COLUMN balance_type_registry.negative_semantics IS 'Semantics of negative balance when allow_negative=true: OVERDRAFT | SHORT_POSITION | PRE_AUTHORIZED | CREDIT_UTILIZATION';
COMMENT ON COLUMN balance_type_registry.zero_floor_enforce IS 'Whether to enforce balance >= 0 as a hard floor (true/false)';
COMMENT ON COLUMN balance_type_registry.currency_scope IS 'Currency scope: SINGLE_CCY | MULTI_CCY | BASE_CCY_ONLY';
COMMENT ON COLUMN balance_type_registry.config_version IS 'Monotonic config version, incremented on each modification';
COMMENT ON COLUMN balance_type_registry.created_by IS 'Operator ID that created this config (passed from upstream domain)';
COMMENT ON COLUMN balance_type_registry.created_at IS 'Config creation timestamp (UTC, microsecond precision)';
COMMENT ON COLUMN balance_type_registry.updated_at IS 'Last update timestamp (UTC, microsecond precision; set explicitly by writer)';
COMMENT ON COLUMN balance_type_registry.last_modified_by IS 'Operator ID that last modified this config';
COMMENT ON COLUMN balance_type_registry.last_modified_at IS 'Timestamp of last modification (UTC, microsecond precision)';
COMMENT ON COLUMN balance_type_registry.change_reason IS 'Free-text reason for the change, required on every modification';

-- MySQL View Layer initialization
CREATE TABLE IF NOT EXISTS journal (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    journal_id       VARCHAR(64) NOT NULL UNIQUE,
    journal_type     VARCHAR(32) NOT NULL,
    request_id       VARCHAR(64) NOT NULL,
    business_event_type  VARCHAR(64),
    business_event_ref   VARCHAR(128),
    value_date       DATE NOT NULL,
    status           VARCHAR(16) NOT NULL,
    cross_period     BOOLEAN DEFAULT FALSE,
    created_at       TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_request_id (request_id),
    INDEX idx_business_event_ref (business_event_ref),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS journal_line (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    journal_line_id  VARCHAR(72) NOT NULL UNIQUE,
    journal_id       VARCHAR(64) NOT NULL,
    leg_id           VARCHAR(64),
    account_id       VARCHAR(64) NOT NULL,
    balance_type     VARCHAR(64) NOT NULL,
    position         VARCHAR(16) NOT NULL DEFAULT 'CURRENT',
    currency         VARCHAR(8) NOT NULL,
    entry_type       VARCHAR(8) NOT NULL,
    amount           DECIMAL(24,8) NOT NULL,
    balance_before   DECIMAL(24,8) NOT NULL,
    balance_after    DECIMAL(24,8) NOT NULL,
    config_version   INT NOT NULL,
    created_at       TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_journal_id (journal_id),
    INDEX idx_account_id (account_id),
    INDEX idx_account_balance (account_id, balance_type, currency),
    FOREIGN KEY (journal_id) REFERENCES journal(journal_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS account (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_id       VARCHAR(64) NOT NULL UNIQUE,
    account_type     VARCHAR(16) NOT NULL,
    display_name     VARCHAR(128),
    owner_id         VARCHAR(64),
    status           VARCHAR(16) NOT NULL,
    created_at       TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_owner_id (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS account_balance (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_id       VARCHAR(64) NOT NULL,
    balance_type     VARCHAR(64) NOT NULL,
    position         VARCHAR(16) NOT NULL DEFAULT 'CURRENT',
    currency         VARCHAR(8) NOT NULL,
    amount           DECIMAL(24,8) NOT NULL DEFAULT 0.00000000,
    frozen_amount    DECIMAL(24,8) NOT NULL DEFAULT 0.00000000,
    locked_amount    DECIMAL(24,8) NOT NULL DEFAULT 0.00000000,
    account_seq      BIGINT NOT NULL DEFAULT 0,
    last_journal_id  VARCHAR(64),
    created_at       TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_account_balance (account_id, balance_type, position, currency),
    INDEX idx_account_id (account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS balance_type_registry (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    type_code        VARCHAR(64) NOT NULL UNIQUE,
    display_name     JSON NOT NULL,
    description      TEXT NOT NULL,
    category         VARCHAR(32) NOT NULL,
    status           VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    sign_convention  VARCHAR(32) NOT NULL,
    allow_negative   BOOLEAN NOT NULL DEFAULT FALSE,
    negative_semantics   VARCHAR(32),
    zero_floor_enforce   BOOLEAN NOT NULL DEFAULT TRUE,
    currency_scope   VARCHAR(32) NOT NULL,
    config_version   INTEGER NOT NULL DEFAULT 1,
    created_by       VARCHAR(64) NOT NULL,
    created_at       TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    last_modified_by VARCHAR(64),
    last_modified_at TIMESTAMP(6),
    change_reason    TEXT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================
-- Sharding physical tables for journal_line (4 shards by default)
-- journal_line is sharded by account_id hash via ShardingSphere-JDBC
-- ============================================================
CREATE TABLE IF NOT EXISTS journal_line_0 LIKE journal_line;
CREATE TABLE IF NOT EXISTS journal_line_1 LIKE journal_line;
CREATE TABLE IF NOT EXISTS journal_line_2 LIKE journal_line;
CREATE TABLE IF NOT EXISTS journal_line_3 LIKE journal_line;

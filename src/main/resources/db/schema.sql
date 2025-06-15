-- JRaft Ledger System Database Schema
-- MySQL Database Schema for JRaft Ledger System
-- User-based ledger with three account types: BROKERAGE, EXCHANGE, AVAILABLE

-- Create database if not exists
CREATE DATABASE IF NOT EXISTS jraft_ledger DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE jraft_ledger;

-- ============================================================================
-- Accounts table - User-based accounts with three types
-- ============================================================================
CREATE TABLE IF NOT EXISTS account (
    account_id VARCHAR(100) NOT NULL PRIMARY KEY COMMENT '账户ID (格式: userId:accountType)',
    user_id VARCHAR(50) NOT NULL COMMENT '用户ID',
    account_type ENUM('brokerage', 'exchange', 'available') NOT NULL COMMENT '账户类型',
    balance DECIMAL(19,4) NOT NULL DEFAULT 0.0000 COMMENT '余额 (4位小数精度)',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version BIGINT DEFAULT 0 COMMENT '版本号(乐观锁)',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标志',
    
    -- Indexes
    INDEX idx_user_id (user_id),
    INDEX idx_account_type (account_type),
    INDEX idx_user_type (user_id, account_type),
    INDEX idx_balance (balance),
    INDEX idx_created_at (created_at),
    INDEX idx_deleted (deleted),
    
    -- Constraints
    CONSTRAINT chk_balance_non_negative CHECK (balance >= 0),
    CONSTRAINT uk_user_account_type UNIQUE (user_id, account_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户账户表';

-- ============================================================================
-- Processed transactions table - Track individual transaction entries
-- ============================================================================
CREATE TABLE IF NOT EXISTS processed_transaction (
    transaction_id VARCHAR(100) NOT NULL PRIMARY KEY COMMENT '交易ID',
    from_account_id VARCHAR(100) NOT NULL COMMENT '转出账户ID',
    to_account_id VARCHAR(100) NOT NULL COMMENT '转入账户ID',
    amount DECIMAL(19,4) NOT NULL COMMENT '转账金额',
    description VARCHAR(500) COMMENT '交易描述',
    idempotent_id VARCHAR(1000) COMMENT '幂等性ID (支持自定义或自动生成的长字符串)',
    processed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '处理时间',
    status VARCHAR(20) DEFAULT 'COMMITTED' COMMENT '交易状态',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标志',
    
    -- Indexes
    INDEX idx_from_account (from_account_id),
    INDEX idx_to_account (to_account_id),
    INDEX idx_processed_at (processed_at),
    INDEX idx_amount (amount),
    INDEX idx_status (status),
    INDEX idx_deleted (deleted),
    INDEX idx_idempotent_id (idempotent_id(255)),
    
    -- Constraints
    CONSTRAINT chk_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_different_accounts CHECK (from_account_id != to_account_id),
    
    -- Foreign keys (removed CASCADE to allow check constraints)
    FOREIGN KEY (from_account_id) REFERENCES account(account_id),
    FOREIGN KEY (to_account_id) REFERENCES account(account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='已处理交易表';

-- ============================================================================
-- Transaction log table - For JRaft operations and audit
-- ============================================================================
CREATE TABLE IF NOT EXISTS transaction_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    log_index BIGINT NOT NULL COMMENT 'Raft日志索引',
    term BIGINT NOT NULL COMMENT 'Raft任期',
    operation_type VARCHAR(50) NOT NULL COMMENT '操作类型',
    operation_data JSON COMMENT '操作数据',
    transaction_id VARCHAR(100) COMMENT '关联的交易ID',
    user_id VARCHAR(50) COMMENT '操作用户ID',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标志',
    
    -- Indexes
    INDEX idx_log_index (log_index),
    INDEX idx_term (term),
    INDEX idx_operation_type (operation_type),
    INDEX idx_transaction_id (transaction_id),
    INDEX idx_user_id (user_id),
    INDEX idx_created_at (created_at),
    INDEX idx_deleted (deleted),
    
    -- Unique constraint for Raft log consistency
    UNIQUE KEY uk_log_index_term (log_index, term)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='交易日志表';

-- ============================================================================
-- Balance snapshots table - For performance optimization
-- ============================================================================
CREATE TABLE IF NOT EXISTS balance_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_id VARCHAR(100) NOT NULL COMMENT '账户ID',
    balance DECIMAL(19,4) NOT NULL COMMENT '快照余额',
    snapshot_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '快照时间',
    log_index BIGINT NOT NULL COMMENT '对应的Raft日志索引',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标志',
    
    -- Indexes
    INDEX idx_account_id (account_id),
    INDEX idx_snapshot_at (snapshot_at),
    INDEX idx_log_index (log_index),
    INDEX idx_deleted (deleted),
    
    -- Foreign key (removed CASCADE for consistency)
    FOREIGN KEY (account_id) REFERENCES account(account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='余额快照表';

-- ============================================================================
-- Insert sample data for testing
-- ============================================================================

-- Sample users with three account types each
INSERT INTO account (account_id, user_id, account_type, balance) VALUES
-- UserA accounts
('UserA:available', 'UserA', 'available', 1000.0000),
('UserA:brokerage', 'UserA', 'brokerage', 5000.0000),
('UserA:exchange', 'UserA', 'exchange', 2000.0000),

-- UserB accounts  
('UserB:available', 'UserB', 'available', 500.0000),
('UserB:brokerage', 'UserB', 'brokerage', 3000.0000),
('UserB:exchange', 'UserB', 'exchange', 1500.0000),

-- Bank accounts (system accounts)
('Bank:available', 'Bank', 'available', 100000.0000),
('Bank:brokerage', 'Bank', 'brokerage', 50000.0000),
('Bank:exchange', 'Bank', 'exchange', 25000.0000),

-- Exchange accounts (system accounts)
('Exchange:available', 'Exchange', 'available', 75000.0000),
('Exchange:brokerage', 'Exchange', 'brokerage', 40000.0000),
('Exchange:exchange', 'Exchange', 'exchange', 60000.0000)

ON DUPLICATE KEY UPDATE 
    balance = VALUES(balance),
    updated_at = CURRENT_TIMESTAMP;

-- Sample processed transactions for testing
INSERT INTO processed_transaction (transaction_id, from_account_id, to_account_id, amount, description, idempotent_id, status) VALUES
('txn_001', 'UserA:available', 'UserB:available', 100.0000, 'Transfer from UserA to UserB', 'sample-idempotent-key-001', 'COMMITTED'),
('txn_002', 'UserA:available', 'Bank:available', 50.0000, 'Transfer from UserA to Bank', 'sample-idempotent-key-002', 'COMMITTED'),
('txn_003', 'UserB:brokerage', 'Exchange:brokerage', 200.0000, 'Brokerage transfer to Exchange', 'sample-idempotent-key-003', 'COMMITTED')

ON DUPLICATE KEY UPDATE 
    processed_at = CURRENT_TIMESTAMP;

-- Sample transaction log entries
INSERT INTO transaction_log (log_index, term, operation_type, transaction_id, user_id, operation_data) VALUES
(1, 1, 'APPLY_TRANSACTION', 'txn_001', 'UserA', '{"from":"UserA:available","to":"UserB:available","amount":100.0000}'),
(2, 1, 'APPLY_TRANSACTION', 'txn_002', 'UserA', '{"from":"UserA:available","to":"Bank:available","amount":50.0000}'),
(3, 1, 'APPLY_TRANSACTION', 'txn_003', 'UserB', '{"from":"UserB:brokerage","to":"Exchange:brokerage","amount":200.0000}')

ON DUPLICATE KEY UPDATE 
    created_at = CURRENT_TIMESTAMP; 
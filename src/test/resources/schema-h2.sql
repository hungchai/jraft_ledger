-- ============================================================================
-- H2 Database Schema for Testing
-- Compatible with H2 in-memory database
-- ============================================================================

-- ============================================================================
-- Account table - Store account information and balances
-- ============================================================================
CREATE TABLE IF NOT EXISTS account (
    account_id VARCHAR(100) NOT NULL PRIMARY KEY,
    user_id VARCHAR(50) NOT NULL,
    account_type VARCHAR(20) NOT NULL,
    balance DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
);

-- Create indexes for account table
CREATE INDEX IF NOT EXISTS idx_user_id ON account(user_id);
CREATE INDEX IF NOT EXISTS idx_account_type ON account(account_type);
CREATE INDEX IF NOT EXISTS idx_balance ON account(balance);
CREATE INDEX IF NOT EXISTS idx_created_at ON account(created_at);

-- ============================================================================
-- Transaction log table - Store all transaction operations
-- ============================================================================
CREATE TABLE IF NOT EXISTS transaction_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    operation_type VARCHAR(50) NOT NULL,
    operation_data TEXT,
    transaction_id VARCHAR(100),
    user_id VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
);

-- Create indexes for transaction_log table
CREATE INDEX IF NOT EXISTS idx_operation_type ON transaction_log(operation_type);
CREATE INDEX IF NOT EXISTS idx_transaction_id ON transaction_log(transaction_id);
CREATE INDEX IF NOT EXISTS idx_user_id_log ON transaction_log(user_id);
CREATE INDEX IF NOT EXISTS idx_created_at_log ON transaction_log(created_at);

-- ============================================================================
-- Processed transactions table - Track individual transaction entries
-- ============================================================================
CREATE TABLE IF NOT EXISTS processed_transaction (
    transaction_id VARCHAR(100) NOT NULL PRIMARY KEY,
    from_account_id VARCHAR(100) NOT NULL,
    to_account_id VARCHAR(100) NOT NULL,
    amount DECIMAL(19,4) NOT NULL,
    description VARCHAR(500),
    idempotent_id VARCHAR(1000),
    processed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(20) DEFAULT 'COMMITTED',
    deleted TINYINT DEFAULT 0
);

-- Create indexes for processed_transaction table
CREATE INDEX IF NOT EXISTS idx_from_account ON processed_transaction(from_account_id);
CREATE INDEX IF NOT EXISTS idx_to_account ON processed_transaction(to_account_id);
CREATE INDEX IF NOT EXISTS idx_processed_at ON processed_transaction(processed_at);
CREATE INDEX IF NOT EXISTS idx_status ON processed_transaction(status);
CREATE INDEX IF NOT EXISTS idx_idempotent_id ON processed_transaction(idempotent_id);

-- ============================================================================
-- Sample data for testing
-- ============================================================================

-- Sample accounts for testing
MERGE INTO account (account_id, user_id, account_type, balance) VALUES
('UserA:available', 'UserA', 'AVAILABLE', 1000.0000),
('UserA:brokerage', 'UserA', 'BROKERAGE', 500.0000),
('UserA:exchange', 'UserA', 'EXCHANGE', 200.0000),
('UserB:available', 'UserB', 'AVAILABLE', 500.0000),
('UserB:brokerage', 'UserB', 'BROKERAGE', 300.0000),
('UserB:exchange', 'UserB', 'EXCHANGE', 100.0000),
('Bank:available', 'Bank', 'AVAILABLE', 1000000.0000),
('Exchange:available', 'Exchange', 'AVAILABLE', 500000.0000),
('Exchange:brokerage', 'Exchange', 'BROKERAGE', 200000.0000),
('Exchange:exchange', 'Exchange', 'EXCHANGE', 100000.0000);

-- Sample processed transactions for testing
MERGE INTO processed_transaction (transaction_id, from_account_id, to_account_id, amount, description, idempotent_id, status) VALUES
('txn_001', 'UserA:available', 'UserB:available', 100.0000, 'Transfer from UserA to UserB', 'sample-idempotent-key-001', 'COMMITTED'),
('txn_002', 'UserA:available', 'Bank:available', 50.0000, 'Transfer from UserA to Bank', 'sample-idempotent-key-002', 'COMMITTED'),
('txn_003', 'UserB:brokerage', 'Exchange:brokerage', 200.0000, 'Brokerage transfer to Exchange', 'sample-idempotent-key-003', 'COMMITTED'); 
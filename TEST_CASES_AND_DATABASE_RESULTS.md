# JRaft Ledger System - Test Cases and Expected Database Results

## Overview
This document provides comprehensive test cases for the JRaft Ledger System and their expected database results. The system implements a distributed ledger with three account types (AVAILABLE, BROKERAGE, EXCHANGE) and supports atomic transfers with idempotency guarantees.

## Database Schema Overview

### Tables Structure
- **account**: Stores user accounts with balances
- **processed_transaction**: Records all completed transactions
- **transaction_log**: JRaft operation logs for consensus
- **balance_snapshot**: Performance optimization snapshots

### Initial Test Data
```sql
-- Sample accounts with initial balances
UserA:available    -> 1000.0000
UserA:brokerage    -> 5000.0000  (MySQL) / 500.0000 (H2)
UserA:exchange     -> 2000.0000  (MySQL) / 200.0000 (H2)
UserB:available    -> 500.0000
UserB:brokerage    -> 3000.0000  (MySQL) / 300.0000 (H2)
UserB:exchange     -> 1500.0000  (MySQL) / 100.0000 (H2)
Bank:available     -> 100000.0000 (MySQL) / 1000000.0000 (H2)
Exchange:available -> 75000.0000 (MySQL) / 500000.0000 (H2)
```

## Test Cases and Expected Results

### 1. Data Initialization Tests

#### Test Case 1.1: `testDataInitializationService()`
**Purpose**: Verify RocksDB initialization from MySQL
**Expected Result**: 
- Service initializes without exceptions
- RocksDB contains data from MySQL

#### Test Case 1.2: `testDataStatusAPI()`
**Purpose**: Check data initialization status endpoint
**API Call**: `GET /api/data/status`
**Expected Response**:
```json
{
  "status": "OK",
  "message": "Data initialization status"
}
```
**HTTP Status**: 200

#### Test Case 1.3: `testDataInitializationAPI()`
**Purpose**: Test data re-initialization endpoint
**API Call**: `POST /api/data/initialize`
**Expected Response**:
```json
{
  "success": true,
  "message": "Data initialized successfully"
}
```
**HTTP Status**: 200

### 2. Account Management Tests

#### Test Case 2.1: `testCreateAccount()`
**Purpose**: Create new account via service
**Action**: Create account for "NewTestUser" with AVAILABLE type
**Expected Database Changes**:
```sql
INSERT INTO account VALUES (
  'NewTestUser:available',
  'NewTestUser', 
  'AVAILABLE',
  0.0000,
  CURRENT_TIMESTAMP,
  CURRENT_TIMESTAMP,
  0,
  0
);
```
**Expected Balance**: 0.0000

#### Test Case 2.2: `testRestApiCreateAccount()`
**Purpose**: Create account via REST API
**API Call**: `POST /api/balance/create`
**Request Body**:
```json
{
  "userId": "APITestUser",
  "accountType": "AVAILABLE"
}
```
**Expected Database Changes**:
```sql
INSERT INTO account VALUES (
  'APITestUser:available',
  'APITestUser',
  'AVAILABLE', 
  0.0000,
  CURRENT_TIMESTAMP,
  CURRENT_TIMESTAMP,
  0,
  0
);
```
**Expected Response**:
```json
{
  "success": true,
  "message": "Account created successfully"
}
```

### 3. Balance Query Tests

#### Test Case 3.1: `testExistingAccountBalances()`
**Purpose**: Verify existing account balances
**Expected Results**:
- UserA:available balance ≥ 0
- UserB:available balance ≥ 0
- Balances match database values

#### Test Case 3.2: `testGetUserBalances()`
**Purpose**: Get all account balances for a user
**Expected Response Structure**:
```json
{
  "userId": "UserA",
  "availableBalance": 1000.0000,
  "brokerageBalance": 5000.0000,
  "exchangeBalance": 2000.0000
}
```

#### Test Case 3.3: `testRestApiGetUserBalance()`
**Purpose**: Get user balances via REST API
**API Call**: `GET /api/balance/user/UserA`
**Expected Response**:
```json
{
  "userId": "UserA",
  "availableBalance": 1000.0000,
  "brokerageBalance": 5000.0000,
  "exchangeBalance": 2000.0000
}
```
**HTTP Status**: 200

#### Test Case 3.4: `testRestApiGetAccountBalance()`
**Purpose**: Get specific account balance
**API Call**: `GET /api/balance/account/UserA/available`
**Expected Response**:
```json
{
  "userId": "UserA",
  "accountType": "AVAILABLE",
  "balance": 1000.0000
}
```

### 4. Transfer Tests

#### Test Case 4.1: `testTransferWithSufficientBalance()`
**Purpose**: Transfer with sufficient balance
**Action**: Transfer 50.00 from UserA:available to UserB:available
**Pre-conditions**: UserA:available balance ≥ 50.00
**Expected Database Changes**:
```sql
-- Account balance updates
UPDATE account SET 
  balance = balance - 50.0000,
  updated_at = CURRENT_TIMESTAMP
WHERE account_id = 'UserA:available';

UPDATE account SET 
  balance = balance + 50.0000,
  updated_at = CURRENT_TIMESTAMP  
WHERE account_id = 'UserB:available';

-- Transaction record
INSERT INTO processed_transaction VALUES (
  'generated-uuid',
  'UserA:available',
  'UserB:available', 
  50.0000,
  'Integration test transfer',
  'generated-idempotent-key',
  CURRENT_TIMESTAMP,
  'COMMITTED',
  0
);
```
**Expected Balance Changes**:
- UserA:available: -50.00
- UserB:available: +50.00

#### Test Case 4.2: `testRestApiTransfer()`
**Purpose**: Transfer via REST API
**API Call**: `POST /api/transfer/single`
**Request Body**:
```json
{
  "fromUserId": "UserA",
  "fromType": "AVAILABLE",
  "toUserId": "UserB",
  "toType": "AVAILABLE", 
  "amount": 25.00,
  "description": "API integration test transfer"
}
```
**Expected Database Changes**: Similar to 4.1 but with 25.00 amount
**Expected Response**:
```json
{
  "success": true,
  "transactionId": "generated-uuid"
}
```

#### Test Case 4.3: `testIdempotentTransfer()`
**Purpose**: Test idempotency protection
**API Call**: `POST /api/transfer/single` (called twice)
**Request Headers**: `Idempotency-Key: test-integration-idempotent-001`
**Expected Behavior**: 
- First call: Processes transfer normally
- Second call: Returns success without duplicate processing
- Only one transaction record in database
**Expected Database State**: Single transaction entry with idempotent_id

#### Test Case 4.4: `testBatchTransfer()`
**Purpose**: Test atomic batch transfers
**Pre-setup**: 
- Create BatchTestA and BatchTestB accounts
- Fund BatchTestA with 100.00 from Bank
**Batch Operations**:
1. BatchTestA → BatchTestB: 10.00
2. BatchTestA → UserB: 20.00
**Expected Database Changes**: All transfers committed atomically or all rolled back
**Expected Final Balances**:
- BatchTestA: 70.00 (100 - 10 - 20)
- BatchTestB: 10.00
- UserB: +20.00 to existing balance

### 5. Real-World Scenario Tests

#### Test Case 5.1: `testRealWorldScenario()`
**Purpose**: Comprehensive real-world transfer scenario
**Action**: UserA transfers 75.00 to UserB (AVAILABLE accounts)
**Pre-conditions**: UserA:available balance ≥ 100.00
**Expected Database Changes**:
```sql
-- Balance updates
UPDATE account SET balance = balance - 75.0000 WHERE account_id = 'UserA:available';
UPDATE account SET balance = balance + 75.0000 WHERE account_id = 'UserB:available';

-- Transaction log
INSERT INTO processed_transaction VALUES (
  'generated-uuid',
  'UserA:available', 
  'UserB:available',
  75.0000,
  'Real world test transfer',
  'generated-idempotent-key',
  CURRENT_TIMESTAMP,
  'COMMITTED',
  0
);
```
**Verification**: Accounting equation balance maintained

### 6. System Health and Monitoring Tests

#### Test Case 6.1: `testIdempotencyCacheStats()`
**Purpose**: Check idempotency cache statistics
**API Call**: `GET /api/admin/idempotency/stats`
**Expected Response**:
```json
{
  "totalEntries": 5,
  "hitRate": 0.85,
  "missRate": 0.15
}
```

#### Test Case 6.2: `testSystemHealthAfterOperations()`
**Purpose**: Verify system health after all operations
**API Calls**: 
- `GET /api/data/status`
- `GET /api/balance/user/UserA`
**Expected**: Both return HTTP 200 with valid responses

### 7. Error Handling Tests

#### Test Case 7.1: Insufficient Balance Transfer
**Action**: Attempt transfer exceeding available balance
**Expected Result**: Transfer fails, no database changes
**Expected Response**: HTTP 400 with error message

#### Test Case 7.2: Non-existent Account Transfer
**Action**: Transfer involving non-existent account
**Expected Result**: Transfer fails with validation error
**Expected Response**: HTTP 404 with error message

#### Test Case 7.3: Invalid Account Type
**Action**: Create account with invalid account type
**Expected Result**: Validation error
**Expected Response**: HTTP 400 with validation message

## Database State Verification Queries

### Check Account Balances
```sql
SELECT account_id, user_id, account_type, balance, updated_at 
FROM account 
ORDER BY user_id, account_type;
```

### Check Transaction History
```sql
SELECT transaction_id, from_account_id, to_account_id, amount, 
       description, processed_at, status
FROM processed_transaction 
ORDER BY processed_at DESC;
```

### Check Transaction Logs
```sql
SELECT id, operation_type, transaction_id, user_id, created_at
FROM transaction_log 
ORDER BY created_at DESC;
```

### Verify Accounting Balance
```sql
-- Total system balance should remain constant
SELECT 
  SUM(CASE WHEN account_type = 'available' THEN balance ELSE 0 END) as total_available,
  SUM(CASE WHEN account_type = 'brokerage' THEN balance ELSE 0 END) as total_brokerage,
  SUM(CASE WHEN account_type = 'exchange' THEN balance ELSE 0 END) as total_exchange,
  SUM(balance) as grand_total
FROM account;
```

## Test Environment Configuration

### H2 Test Database
- **Profile**: `test`
- **URL**: In-memory H2 database
- **Schema**: `src/test/resources/schema-h2.sql`
- **Initial Data**: Automatically loaded via MERGE statements

### MySQL Production Database  
- **Profile**: `local` or `raft`
- **Schema**: `src/main/resources/db/schema.sql`
- **Initial Data**: Loaded via INSERT statements with ON DUPLICATE KEY UPDATE

## Performance Expectations

### Response Times
- Account creation: < 100ms
- Balance queries: < 50ms  
- Single transfers: < 200ms
- Batch transfers: < 500ms (depending on batch size)

### Throughput
- Balance queries: > 1000 TPS
- Transfers: > 500 TPS
- Account creation: > 200 TPS

## Idempotency Guarantees

All transfer operations support idempotency through:
- **Idempotency-Key** header in REST API
- **idempotent_id** field in processed_transaction table
- Cache-based duplicate detection
- Automatic key generation if not provided

## Data Consistency Guarantees

- **ACID Transactions**: All database operations are atomic
- **Double-Entry Bookkeeping**: Every transfer maintains balance equation
- **JRaft Consensus**: Distributed consistency across cluster nodes
- **Optimistic Locking**: Version-based conflict resolution
- **Referential Integrity**: Foreign key constraints maintained 
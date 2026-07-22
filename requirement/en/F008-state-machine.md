# F-008 State Machine Design — Functional Requirements Specification

**Document Version**: v0.2
**Feature**: F-008 State Machine Design
**System**: Next-Gen Internal Ledger Platform
**Status**: Draft for Review
**Dependency**: ADR-001 (Raft + CQRS Architecture)

> **v0.2 Change Summary**: `BalanceEntry` added `accountSeq` field (per-account monotonically increasing sequence number), Apply flow updated synchronously, Snapshot serialization requirements supplemented, AC-11 added.

---

## 1. Feature Overview

The State Machine is the core computing unit of the Ledger Platform, running on the Raft Leader node, responsible for:

1. **Apply Raft Log**: Receiving committed Raft Commands and executing accounting calculations
2. **Maintain in-memory Balance**: Latest balances for all accounts and all Balance Types
3. **Generate Journal records**: Each apply produces complete journal + journal_line
4. **Persist to RocksDB**: Ensuring recoverability after crash
5. **Periodic Snapshot**: Controlling Raft Log growth and speeding up failure recovery

---

## 2. Data Structures

### 2.1 In-Memory Balance Store

```java
// Account balance key
record AccountBalanceKey(
    String accountId,
    String balanceType,
    String currency
) {}

// Account balance entry
record BalanceEntry(
    BigDecimal amount,          // Current balance
    long stateVersion,          // Last updated Raft Log Index
    long accountSeq,            // [v0.2 new] per-account monotonically increasing sequence number
                                // Dimension: accountId + balanceType + currency
                                // Increments by 1 on each balance change (Posting/Reversal/Adjustment)
                                // Starts from 1, independent of raftLogIndex
    String lastJournalId,       // Last Journal ID
    Instant lastUpdatedAt       // Last update time
) {}

// Balance Store: lock-free read, Account Worker serial write
ConcurrentHashMap<AccountBalanceKey, BalanceEntry> balanceStore;
```

### 2.2 In-Memory Idempotency Store

```java
// Idempotency key: requestId (including posting / reversal / adjustment)
// Value: completed result summary
record IdempotencyEntry(
    String requestId,
    String status,          // COMPLETED / REJECTED
    String journalId,       // journalId on success
    List<String> errors,    // Error list on failure
    Instant completedAt
) {}

// TTL: Retained for 24 hours (configurable) to prevent unbounded map growth
// Implementation: ConcurrentHashMap + periodic eviction job
ConcurrentHashMap<String, IdempotencyEntry> idempotencyStore;
```

### 2.3 Account Metadata Store

```java
// Account status (ACTIVE / FROZEN / CLOSED)
ConcurrentHashMap<String, AccountMeta> accountMetaStore;

record AccountMeta(
    String accountId,
    String status,
    Instant createdAt,
    Set<String> allowedBalanceTypes
) {}
```

### 2.4 Balance Type Config Store

```java
// Balance Type configuration (from F-001 Balance Type Registry)
// Loaded from RocksDB at State Machine startup
// Updated via special RaftCommand when configuration changes
ConcurrentHashMap<String, BalanceTypeConfig> balanceTypeConfigStore;

record BalanceTypeConfig(
    String typeCode,
    boolean allowNegative,
    String negativeSemantics,   // PRE_AUTHORIZED / OVERDRAFT / etc.
    String signConvention,      // NORMAL_CREDIT / NORMAL_DEBIT
    String formula,             // Optional, used for FORMULA type
    int configVersion
) {}
```

---

## 3. Raft Command Types

All accounting operations are serialized into RaftCommand and submitted to the Raft Group:

| Command Type | Source | Description |
|---|---|---|
| `POSTING_CMD` | F-002 Posting API | Normal posting |
| `REVERSAL_CMD` | F-004 Reversal API | Reverse existing Journal |
| `ADJUSTMENT_CMD` | F-003 Manual Adjustment API | Manual adjustment |
| `ACCOUNT_CREATE_CMD` | Account management API | Create new account |
| `ACCOUNT_FREEZE_CMD` | Account management API | Freeze account |
| `BALANCE_TYPE_CONFIG_CMD` | F-001 Registry management API | Update Balance Type configuration |
| `SNAPSHOT_CMD` | Internal system | Trigger State Machine Snapshot |

---

## 4. Apply Flow

### 4.1 POSTING_CMD Apply

```
Input: PostingCommand {
  requestId, businessEventType, businessEventRef,
  valueDate, legs: [ { legId, lines: [ JournalLineCmd ] } ]
}

Execution steps:

1. Idempotency check
   if idempotencyStore.contains(requestId):
     return idempotencyStore.get(requestId)  // Return original result directly

2. Account status check
   for each accountId in command:
     if accountMetaStore.get(accountId).status != ACTIVE:
       return REJECTED(ACCOUNT_FROZEN)

3. Balance validation (read balanceStore, compute after value)
   for each JournalLineCmd:
     balanceTypeConfig = balanceTypeConfigStore.get(balanceType)
     currentBalance = balanceStore.get(AccountBalanceKey)
     afterBalance = compute(currentBalance, entryType, amount, signConvention)

     if !allowNegative && afterBalance < 0:
       return REJECTED(INSUFFICIENT_BALANCE)
     if allowNegative && afterBalance > 0:
       return REJECTED(CREDIT_EXCEEDS_LIMIT)

4. Generate Journal
   journalId = generateJournalId(raftLogIndex)
   journal = Journal {
     journalId, journalType=NORMAL,
     requestId, businessEventType, businessEventRef,
     valueDate, status=CONFIRMED,
     createdAt=now()
   }

5. Generate JournalLine
   for each JournalLineCmd:
     balanceBefore = balanceStore.get(key).amount
     balanceAfter = compute(balanceBefore, ...)
     journalLine = JournalLine {
       journalLineId, journalId, legId,
       accountId, balanceType, currency,
       entryType, amount,
       balanceBefore, balanceAfter,
       configVersion, createdAt=now()
     }

6. Atomically update balanceStore (including accountSeq increment) [v0.2 update]
   for each journalLine:
     currentEntry = balanceStore.get(key)
     nextSeq = (currentEntry != null) ? currentEntry.accountSeq() + 1 : 1
     balanceStore.put(key, BalanceEntry(
         balanceAfter, raftLogIndex, nextSeq, journalId, now()
     ))
   // REVERSAL_CMD and ADJUSTMENT_CMD balanceStore updates also increment accountSeq

7. Persist to RocksDB
   rocksDB.put(CF_JOURNAL, journalId, serialize(journal))
   for each journalLine:
     rocksDB.put(CF_JOURNAL_LINE, journalLineId, serialize(journalLine))
   for each AccountBalanceKey:
     rocksDB.put(CF_BALANCE, key, serialize(balanceStore.get(key)))
   // The above three puts are in the same RocksDB WriteBatch, atomically committed
   // CF_BALANCE serialized BalanceEntry includes accountSeq

8. Update idempotencyStore
   idempotencyStore.put(requestId, IdempotencyEntry(COMPLETED, journalId, ...))

9. Return PostingResult
```

### 4.2 REVERSAL_CMD Apply

```
1. Idempotency check (same as above)
2. Load original Journal + all JournalLines
3. Validate: original Journal status = CONFIRMED (not yet reversed)
4. Generate Reversal Journal
5. Generate mirrored JournalLine (DEBIT ↔ CREDIT swapped)
6. Skip sign semantics validation (Reversal is a hedge, inherently valid)
7. Atomically update balanceStore (rollback related balances, accountSeq also increments)
8. Update original Journal status = REVERSED
9. Write to RocksDB, update idempotencyStore, return result
```

### 4.3 ADJUSTMENT_CMD Apply

```
1. Idempotency check (same as above)
2. Read balanceStore for sign semantics validation (must comply with allowNegative rule)
3. Generate Adjustment Journal (journalType=MANUAL_ADJUSTMENT)
4. Atomically update balanceStore (accountSeq also increments)
5. Write to RocksDB, update idempotencyStore, return result
```

---

## 5. RocksDB Storage Design

### 5.1 Column Family Design

```
CF_JOURNAL          Stores Journal header records
CF_JOURNAL_LINE     Stores JournalLine records
CF_BALANCE          Stores account balance snapshots (latest values, including accountSeq)
CF_IDEMPOTENCY      Stores idempotency records (requestId → result)
CF_ACCOUNT_META     Stores account metadata
CF_BALANCE_TYPE     Stores Balance Type configurations
CF_SM_SNAPSHOT      Stores State Machine Snapshots
```

### 5.2 Key Design

```
CF_JOURNAL:
  Key: journal_id (lexicographic order)
  → Fast point lookup by journal_id

CF_JOURNAL_LINE:
  Key: journal_id + "#" + journal_line_id
  → Scan by journal_id prefix to retrieve all lines of a Journal

CF_BALANCE:
  Key: account_id + "#" + balance_type + "#" + currency
  → Scan by account_id prefix to retrieve all balances of an account

CF_IDEMPOTENCY:
  Key: request_id
  Value: Serialized IdempotencyEntry + TTL timestamp
```

### 5.3 WriteBatch Atomicity Guarantee

Each apply of a RaftCommand packs all RocksDB write operations into a `WriteBatch`, ensuring journal / journal_line / balance (including accountSeq) are atomically persisted:

```java
WriteBatch batch = new WriteBatch();
batch.put(CF_JOURNAL, journalKey, journalBytes);
batch.put(CF_JOURNAL_LINE, line1Key, line1Bytes);
batch.put(CF_JOURNAL_LINE, line2Key, line2Bytes);
batch.put(CF_BALANCE, balKey1, balBytes1);  // BalanceEntry includes accountSeq
batch.put(CF_BALANCE, balKey2, balBytes2);
rocksDB.write(writeOptions, batch);
// WriteBatch write is atomic: either all succeed or all fail
```

---

## 6. State Machine Snapshot

### 6.1 Snapshot Trigger Conditions

| Trigger Condition | Description |
|---|---|
| Raft Log reaches 100,000 entries | Auto-triggered to prevent unbounded Log growth |
| Accounting period close | Forced Snapshot at EOD as reconciliation baseline |
| Manual trigger | Management API for upgrades or pre-disaster recovery |

### 6.2 Snapshot Content [v0.2 update]

```
Snapshot includes:
  1. Complete snapshot of all accounts' balanceStore (including accountSeq of each BalanceEntry)
  2. Snapshot of all accounts' accountMetaStore
  3. Snapshot of all Balance Type configurations
  4. Corresponding Raft Log Index (lastAppliedIndex)
  5. Generation timestamp

Snapshot format:
  Serialized to Protobuf / Kryo, written to CF_SM_SNAPSHOT
  Also saved to local disk via SOFAJRaft's SnapshotWriter
  Follower can directly copy Snapshot to accelerate new node joining

⚠️ Important: accountSeq must be included in BalanceEntry's serialization schema.
   If this field is omitted, accountSeq will reset to zero after recovery from Snapshot restart,
   causing downstream consumers to misjudge event stream gap.

Protobuf example:
  message BalanceEntry {
    string amount         = 1;
    int64  stateVersion   = 2;
    int64  accountSeq     = 3;  // Must be included
    string lastJournalId  = 4;
    int64  lastUpdatedAt  = 5;
  }
```

### 6.3 Failure Recovery Flow

```
Leader crash → New Leader elected

New Leader recovery steps:
  1. Load latest Snapshot from CF_SM_SNAPSHOT
     → Restore balanceStore (including accountSeq) / accountMetaStore / balanceTypeConfigStore
  2. Replay all Commands after lastAppliedIndex from Raft Log
     → Apply one by one, making up all changes after Snapshot (accountSeq continues to increment)
  3. State Machine fully recovered, begin serving

Recovery time estimate:
  Snapshot load: < 10 seconds (depends on number of accounts)
  Raft Log replay: < 30 seconds (100,000 logs × 0.3ms/cmd)
  Total: < 1 minute (normal scenario)
```

---

## 7. Learner Synchronization Design

### 7.1 Learner Role

Learner is a non-voting member of Raft, receiving Raft Log from Leader but not participating in elections:

```
Raft Leader
    │
    │ replicate Raft Log (asynchronous, does not block Quorum)
    ▼
Raft Learner
    │
    ▼
  Learner State Machine
    │ apply Raft Log, convert accounting events to MySQL write operations
    ▼
  MySQL View Layer
    ├─ journal (for F-006 Journal Query)
    ├─ journal_line (for F-006 Journal Query)
    ├─ account_balance (for F-007 Reconciliation)
    └─ balance_snapshot (for F-005 As-of Query)
```

### 7.2 Synchronization Latency

- Normal case: Learner lags behind Leader < 1 second
- High load: < 5 seconds (Learner has write buffer for batch MySQL writes)
- Query side indicates `dataSource` so callers know data may be slightly stale

### 7.3 Learner MySQL Write Design

To avoid Learner becoming a bottleneck, Learner adopts batch write strategy:

```
Learner buffers 500ms or 1000 Raft Log entries (whichever comes first)
→ Batch INSERT INTO journal_line VALUES (...)
→ Batch UPDATE account_balance SET ...
→ commit
```

---

## 8. Cold Account Management

Account count may reach millions; it is impossible to keep all accounts resident in memory:

```
Active Set: Accounts with transactions in the past 24 hours → Resident in balanceStore (memory)
Inactive Set: No transactions for over 24 hours → Evicted from balanceStore, kept only in RocksDB

Reading inactive account balances:
  1. Query balanceStore → miss
  2. Read from RocksDB CF_BALANCE (< 1ms), including accountSeq
  3. Load into balanceStore (warm up)

Writing inactive accounts:
  Account Worker first loads balance from RocksDB (including accountSeq) on startup
  → Then executes normal apply flow, accountSeq continues to increment from RocksDB value
```

---

## 9. Performance Targets

| Operation | Target | Description |
|---|---|---|
| Balance read (Active account) | < 0.1ms | ConcurrentHashMap direct read |
| Balance read (Inactive account) | < 1ms | RocksDB read |
| RaftCommand Apply (single account) | < 1ms | State Machine + RocksDB WriteBatch |
| RaftCommand Apply (multi-account RFQ) | < 2ms | Multi-account WriteBatch |
| State Machine Snapshot (1M accounts) | < 30s | Batch serialization |
| Failure recovery (Snapshot + 100k Replay) | < 1 min | See 6.3 |

---

## 10. Acceptance Criteria

| # | Acceptance Condition | Test Method |
|---|---|---|
| AC-01 | After posting, in-memory Balance is updated immediately with no delay | Functional Test |
| AC-02 | Same requestId hits in idempotencyStore, returns original result directly without re-applying | Idempotency Test |
| AC-03 | RocksDB WriteBatch atomicity: simulate crash halfway through write, data is consistent after restart | Failure Test |
| AC-04 | After State Machine Snapshot, recovered Balance from Snapshot is exactly the same as before Snapshot | Recovery Test |
| AC-05 | Failure recovery (Snapshot + Replay) completes within 1 minute | Performance Test |
| AC-06 | After inactive account is evicted, next access can correctly warm up from RocksDB | Functional Test |
| AC-07 | Learner sync latency < 1 second under normal load | Consistency Test |
| AC-08 | For 1M account State Machine, Balance read P95 ≤ 0.1ms (Active) / ≤ 1ms (Inactive) | Performance Test |
| AC-09 | After BALANCE_TYPE_CONFIG_CMD apply, new allowNegative rule takes effect immediately | Functional Test |
| AC-10 | Multi-Account RaftCommand (RFQ scenario) is atomically applied in State Machine without partial updates | Atomicity Test |
| AC-11 | BalanceEntry's accountSeq is included in State Machine Snapshot serialization; after recovery from Snapshot restart, accountSeq is exactly the same as before recovery and must not reset to 0 | Failure Recovery Test |

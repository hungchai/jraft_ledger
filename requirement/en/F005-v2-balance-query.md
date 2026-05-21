# F-005 v2 Balance Query & Snapshot (Raft Architecture Update)

**Document Version**: v0.2 (Updated based on ADR-001)  
**Feature**: F-005 Balance Query & Snapshot  
**System**: Next-Gen Internal Ledger Platform  
**Status**: Draft for Review  
**Change Summary**: Real-time balance query path changed from MySQL to in-memory State Machine; snapshot mechanism remains unchanged, written to MySQL by Learner

---

## 1. Architectural Prerequisites (per ADR-001)

| Query Type | Data Source | Latency Target |
|---|---|---|
| Real-time Balance Query | Raft Leader in-memory State Machine | P95 ≤ 2ms |
| As-of Historical Snapshot Query | MySQL View Layer (Learner sync) | P95 ≤ 30ms |
| EOD Snapshot Query | MySQL View Layer | P95 ≤ 30ms |
| Journal Replay (fallback) | MySQL journal_line | P95 ≤ 5s |

---

## 2. Real-Time Balance Query

### 2.1 Query Path

```
Client → Ledger Service (Leader node)
                │
                ▼
    in-memory State Machine
    ConcurrentHashMap<AccountKey, BalanceMap>
                │
                ▼
         Direct read and return
         No network, no disk
         P95 < 0.5ms (read itself)
         + network latency ≈ 1–2ms
         = P95 ≤ 2ms ✅
```

**Important**: Balance queries must be routed to the **Raft Leader node**; reading from a Follower may return stale data.

### 2.2 API (Unchanged)

```
GET /ledger/accounts/{accountId}/balances
    ?types=AVAILABLE_BALANCE,TRADE_AHEAD_BALANCE
    &currency=USD
```

### 2.3 Response New Fields

```json
{
  "accountId": "CLIENT_ACC_001",
  "currency": "USD",
  "queryTime": "2026-05-16T10:35:00.000Z",
  "isRealtime": true,
  "dataSource": "STATE_MACHINE",
  "raftLeaderId": "node-001",
  "balances": [
    {
      "typeCode": "AVAILABLE_BALANCE",
      "amount": 200000.00,
      "allowNegative": false,
      "configVersion": 3,
      "lastJournalId": "JNL-20260516-000012345",
      "stateVersion": 1024
    },
    {
      "typeCode": "TRADE_AHEAD_BALANCE",
      "amount": -45000.00,
      "allowNegative": true,
      "negativeSemantics": "PRE_AUTHORIZED",
      "configVersion": 1,
      "stateVersion": 987
    }
  ]
}
```

New field descriptions:
- `dataSource`: `STATE_MACHINE` (in-memory) / `EOD_SNAPSHOT` / `JOURNAL_REPLAY`
- `raftLeaderId`: Returns the current Leader node ID, for diagnostic purposes
- `stateVersion`: State Machine version number (i.e. Raft Log Index), for tracking

---

## 3. Batch Balance Query (Unchanged, Performance Significantly Improved)

```
POST /ledger/accounts/balances/batch
```

Because reads are from the in-memory State Machine, batch query performance is significantly improved:

| Metric | v0.1 (MySQL) | v0.2 (State Machine) |
|---|---|---|
| 100-account batch query P95 | 50ms | ≤ 5ms |
| 200-account batch query P95 | 100ms | ≤ 10ms |

---

## 4. State Machine Internal Data Structure

```java
// In-memory State Machine balance storage structure
// Key: AccountKey = (accountId, balanceType, currency)
// Value: BalanceEntry

class BalanceEntry {
    BigDecimal amount;        // Current balance
    long stateVersion;        // Corresponding Raft Log Index
    String lastJournalId;     // Last Journal ID
    Instant lastUpdatedAt;    // Last update time
}

ConcurrentHashMap<AccountKey, BalanceEntry> balanceStore;
```

**Reads are lock-free** (Account Worker writes; readers perform snapshot reads only). Under Java 21, ConcurrentHashMap read operations are essentially contention-free.

---

## 5. As-of Historical Snapshot Query (Architecture Unchanged, Data Source Adjusted)

As-of queries read from the MySQL View Layer (data already synced by Learner):

```
Query logic priority:

1. Find the nearest snapshot before the asOf time from MySQL balance_snapshot
   ├─ Found → Return, marked dataSource=EOD_SNAPSHOT
   └─ Not found → Perform Journal Replay from MySQL journal_line

Note: As-of queries do not read the in-memory State Machine
because the State Machine only keeps the "current" state.
Historical states are in the MySQL View Layer or RocksDB snapshot.
```

---

## 6. EOD Snapshot Mechanism

### 6.1 Snapshot Generation Method (Updated)

v0.2 EOD Snapshots have two sources:

**Source A: State Machine Snapshot (Recommended)**
- The Raft Leader periodically (or on demand) takes a snapshot of the State Machine
- The snapshot contains current balances for all accounts and all balance types
- Synced to MySQL balance_snapshot table via Learner
- Advantage: Fully accurate, consistent with in-memory state

**Source B: Triggered after Learner Incremental Sync**
- Learner continuously syncs journal_line to MySQL
- When the accounting period closes, Learner triggers an EOD Snapshot Job that aggregates a snapshot from MySQL journal_line
- Advantage: Does not depend on Leader; Learner can complete independently

### 6.2 Snapshot Trigger

```
Trigger conditions:
  1. Accounting period close (F-009 AccountingPeriod close)
  2. Scheduled task (daily at 23:59)
  3. Manual trigger (admin API)
  4. State Machine Snapshot (automatically triggered by Raft every 100k logs)
```

---

## 7. Consistency Guarantees

| Scenario | Consistency Guarantee |
|---|---|
| Real-time Balance Query (read State Machine) | **Strong consistency**: Always the latest committed state |
| As-of Snapshot Query (read MySQL) | **Eventual consistency**: May lag behind Leader by up to 1 second |
| EOD Snapshot | **Strong consistency**: Generated from State Machine Snapshot |
| Reconciliation (read MySQL) | **Eventual consistency**; reconciliation allows minute-level delay |

---

## 8. Acceptance Criteria

| # | Acceptance Condition | Test Method |
|---|---|---|
| AC-01 | Real-time Balance Query reads in-memory State Machine, P95 ≤ 2ms | Performance Test |
| AC-02 | After Posting completes, the next Balance Query immediately reflects the new balance (no delay) | Consistency Test |
| AC-03 | `TRADE_AHEAD_BALANCE` negative balance is correctly returned with `dataSource=STATE_MACHINE` | Functional Test |
| AC-04 | Batch query for 200 accounts, P95 ≤ 10ms | Performance Test |
| AC-05 | As-of query returns correct historical balance, `dataSource=EOD_SNAPSHOT` or `JOURNAL_REPLAY` | Functional Test |
| AC-06 | After Raft Leader switch, the new Leader's Balance Query results are consistent with the old Leader | Failure Test |
| AC-07 | EOD Snapshot balance is fully consistent with State Machine Snapshot balance | Reconciliation Test |

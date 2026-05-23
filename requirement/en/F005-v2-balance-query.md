# F-005 v2 Balance Query & Snapshot (Raft Architecture Update)

**Document Version**: v0.3 (Updated for `position` field — CURRENT/LOCKED/FROZEN sub-balances)  
**Feature**: F-005 Balance Query & Snapshot  
**System**: Next-Gen Internal Ledger Platform  
**Status**: Draft for Review  
**Change Summary**: Real-time balance query path changed from MySQL to in-memory State Machine; v0.3 adds `position` field for sub-balance tracking (CURRENT/LOCKED/FROZEN)

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

### 2.2 API

```
GET /ledger/balances
    ?accountId={accountId}
    &balanceType={balanceType}
    &currency={currency}
    [&position={position}]     // Optional: CURRENT, LOCKED, FROZEN
```

**Query Types:**
- **Without position param**: Returns aggregated balance across all positions
- **With position param**: Returns single position balance

### 2.3 Response Structure

**Aggregated Response (no position param):**

```json
{
  "accountId": "CLIENT_ACC_001",
  "balanceType": "BROKERAGE_BALANCE",
  "currency": "USD",
  "amount": 250000.00,
  "positions": {
    "CURRENT": 200000.00,
    "LOCKED": 50000.00,
    "FROZEN": 0.00
  },
  "allowNegative": false,
  "dataSource": "STATE_MACHINE"
}
```

**Single Position Response (with position param):**

```json
{
  "accountId": "CLIENT_ACC_001",
  "balanceType": "BROKERAGE_BALANCE",
  "currency": "USD",
  "amount": 50000.00,
  "positions": {
    "LOCKED": 50000.00
  },
  "allowNegative": false,
  "dataSource": "STATE_MACHINE"
}
```

---

## 3. Batch Balance Query

```
POST /ledger/balances/batch
Body: [
  { "accountId": "A", "balanceType": "BROKERAGE_BALANCE", "position": "CURRENT", "currency": "USD" },
  { "accountId": "A", "balanceType": "BROKERAGE_BALANCE", "position": "LOCKED", "currency": "USD" }
]
```

**Note**: Batch query requires `position` field for each key.

| Metric | v0.2 (State Machine) |
|---|---|
| 100-account batch query P95 | ≤ 5ms |
| 200-account batch query P95 | ≤ 10ms |

---

## 4. State Machine Internal Data Structure

```java
// In-memory State Machine balance storage structure
// Key: AccountBalanceKey = (accountId, balanceType, position, currency)
// Value: BalanceEntry

class BalanceEntry {
    BigDecimal amount;        // Current balance for this position
    long stateVersion;        // Corresponding Raft Log Index
    String lastJournalId;     // Last Journal ID
    Instant lastUpdatedAt;    // Last update time
}

ConcurrentHashMap<AccountBalanceKey, BalanceEntry> balanceStore;
```

**Positions:**
- `CURRENT` — Available for use
- `LOCKED` — Held/pending (e.g., unsettled trades)
- `FROZEN` — Suspended (e.g., compliance holds)

**Note**: LOCKED and FROZEN positions cannot go negative (enforced at State Machine level).

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

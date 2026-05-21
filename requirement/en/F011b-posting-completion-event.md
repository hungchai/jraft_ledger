# F-011b Posting Completion Event — Kafka Output

**Document Version**: v0.1  
**Feature**: F-011b Posting Completion Event  
**System**: Next-Gen Internal Ledger Platform  
**Status**: Draft for Review  
**Dependencies**: F-002 Posting API, F-011 Balance Change Event, F-008 State Machine

---

## 1. Feature Overview

Each time the Raft State Machine completes processing for a `requestId` (whether `COMPLETED` or `REJECTED`), a `PostingCompletionEvent` is immediately sent to Kafka.

This event is oriented toward **caller business systems** (e.g. RFQ Engine, Order Management, Withdrawal Service), allowing them to asynchronously perceive the posting result without polling the API.

### Relationship with F-011 BalanceChangeEvent

| | F-011 BalanceChangeEvent | F-011b PostingCompletionEvent |
|---|---|---|
| **Granularity** | One event per JournalLine | One event per requestId |
| **Trigger** | Any balance change | Posting / Reversal / Manual Adj completion |
| **Primary consumers** | Risk Engine, VAMP, transaction notification | Caller business systems |
| **Includes result** | ❌ (only delta) | ✅ (complete legs + balanceBefore/After) |
| **Includes REJECTED** | ❌ (no balance change) | ✅ (includes error reason) |

The two events are **complementary**; downstream subscribes as needed; neither replaces the other.

---

## 2. Trigger Scenarios

| Scenario | `result` | Description |
|---|---|---|
| Posting succeeds Raft commit | `COMPLETED` | All legs atomically completed |
| Posting fails business validation (insufficient balance, etc.) | `REJECTED` | All account balances unchanged |
| Reversal succeeds | `COMPLETED` | commandType=REVERSAL |
| Manual Adjustment Checker approve and complete | `COMPLETED` | commandType=MANUAL_ADJUSTMENT |
| Raft commit fails (Leader crash, etc.) | ❌ Not sent | Caller retries on timeout; sent normally after retry |

---

## 3. Kafka Topic Design

```
Topic Name:  ledger.posting.completion.v1
Partitions:  64
Retention:   7 days
Compression: LZ4
```

### Partition Key Strategy

```
partitionKey = requestId
```

**Design rationale**:
- Idempotent retry events for the same `requestId` land in the same partition, convenient for downstream ordered consumption and deduplication
- `requestId` is evenly distributed (UUID v7), no hotspot
- Caller can precisely route when subscribing by `requestId`

---

## 4. Event Structure (PostingCompletionEvent)

### 4.1 COMPLETED Example

```json
{
  "eventId": "evt-01JWXYZ999888777ABCDEF",
  "eventType": "POSTING_COMPLETION",
  "eventVersion": "1.0",
  "occurredAt": "2026-05-17T23:30:00.234567890Z",

  "idempotencyKey": "req-550e8400-e29b-41d4-a716-446655440000",

  "requestId": "req-550e8400-e29b-41d4-a716-446655440000",
  "commandType": "POSTING",
  "businessEventType": "RFQ_SETTLEMENT",
  "businessEventRef": "RFQ-2026051600123",

  "result": "COMPLETED",
  "journalId": "JNL-20260516-000012345",
  "bookedAt": "2026-05-17T23:30:00.123456789Z",
  "raftLogIndex": 100245,

  "legs": [
    {
      "legId": "leg-001",
      "postingType": "TRADE_SETTLEMENT",
      "lines": [
        {
          "journalLineId": "JLL-01JWXYZ-001",
          "accountId": "CLIENT_ACC_001",
          "balanceType": "AVAILABLE_BALANCE",
          "currency": "USD",
          "entryType": "DEBIT",
          "amount": "800000.00",
          "balanceBefore": "1000000.00",
          "balanceAfter": "200000.00"
        },
        {
          "journalLineId": "JLL-01JWXYZ-002",
          "accountId": "COMPANY_FX_ACC",
          "balanceType": "AVAILABLE_BALANCE",
          "currency": "USD",
          "entryType": "CREDIT",
          "amount": "800000.00",
          "balanceBefore": "5000000.00",
          "balanceAfter": "5800000.00"
        }
      ]
    },
    {
      "legId": "leg-002",
      "postingType": "TRADE_SETTLEMENT",
      "lines": [
        {
          "journalLineId": "JLL-01JWXYZ-003",
          "accountId": "COMPANY_FX_ACC",
          "balanceType": "AVAILABLE_BALANCE",
          "currency": "HKD",
          "entryType": "DEBIT",
          "amount": "6240000.00",
          "balanceBefore": "10000000.00",
          "balanceAfter": "3760000.00"
        },
        {
          "journalLineId": "JLL-01JWXYZ-004",
          "accountId": "CLIENT_ACC_001",
          "balanceType": "AVAILABLE_BALANCE",
          "currency": "HKD",
          "entryType": "CREDIT",
          "amount": "6240000.00",
          "balanceBefore": "500000.00",
          "balanceAfter": "6740000.00"
        }
      ]
    }
  ],

  "errors": null,

  "traceId": "trace-abc123",
  "metadata": {
    "operatorId": "system",
    "sourceSystem": "LEDGER"
  }
}
```

### 4.2 REJECTED Example

```json
{
  "eventId": "evt-01JWXYZ999888777FEDCBA",
  "eventType": "POSTING_COMPLETION",
  "eventVersion": "1.0",
  "occurredAt": "2026-05-17T23:30:01.000000000Z",

  "idempotencyKey": "req-550e8400-e29b-41d4-a716-446655440001",

  "requestId": "req-550e8400-e29b-41d4-a716-446655440001",
  "commandType": "POSTING",
  "businessEventType": "RFQ_SETTLEMENT",
  "businessEventRef": "RFQ-2026051600124",

  "result": "REJECTED",
  "journalId": null,
  "bookedAt": null,
  "raftLogIndex": null,

  "legs": [],

  "errors": [
    {
      "errorCode": "INSUFFICIENT_BALANCE",
      "accountId": "CLIENT_ACC_001",
      "balanceType": "AVAILABLE_BALANCE",
      "currency": "USD",
      "required": "800000.00",
      "available": "100000.00"
    }
  ],

  "traceId": "trace-def456",
  "metadata": {
    "operatorId": "system",
    "sourceSystem": "LEDGER"
  }
}
```

---

## 5. Field Descriptions

### 5.1 Event Identification

| Field | Type | Description |
|---|---|---|
| `eventId` | `string` | Event unique ID (UUID v7) |
| `eventType` | `string` | Fixed value `POSTING_COMPLETION` |
| `eventVersion` | `string` | Schema version |
| `occurredAt` | `timestamp` | State Machine apply completion time (UTC, nanoseconds) |
| `idempotencyKey` | `string` | Same as `requestId`; downstream deduplicates by this |

### 5.2 Request Identification

| Field | Type | Description |
|---|---|---|
| `requestId` | `string` | Original request idempotency key |
| `commandType` | `enum` | `POSTING` / `REVERSAL` / `MANUAL_ADJUSTMENT` |
| `businessEventType` | `string` | Business event type (e.g. `RFQ_SETTLEMENT`) |
| `businessEventRef` | `string` | Business event reference (e.g. RFQ ID) |

### 5.3 Result

| Field | Type | COMPLETED | REJECTED |
|---|---|---|---|
| `result` | `enum` | `COMPLETED` | `REJECTED` |
| `journalId` | `string` | Generated Journal ID | `null` |
| `bookedAt` | `timestamp` | Raft commit time | `null` |
| `raftLogIndex` | `long` | Raft log index | `null` |
| `legs` | `list` | Complete legs + all lines with balanceBefore/After | `[]` (empty) |
| `errors` | `list` | `null` | Error detail list |

### 5.4 JournalLine (within legs)

| Field | Type | Description |
|---|---|---|
| `journalLineId` | `string` | JournalLine unique ID |
| `accountId` | `string` | Account ID |
| `balanceType` | `string` | Balance Type |
| `currency` | `string` | Currency |
| `entryType` | `enum` | `DEBIT` / `CREDIT` |
| `amount` | `decimal string` | Amount (positive) |
| `balanceBefore` | `decimal string` | Balance before change (State Machine confirmed value) |
| `balanceAfter` | `decimal string` | Balance after change (State Machine confirmed value) |

### 5.5 Error (within errors, for REJECTED)

| Field | Type | Description |
|---|---|---|
| `errorCode` | `string` | Error code (same as F-002 validation rules) |
| `accountId` | `string` | Involved account (if any) |
| `balanceType` | `string` | Involved balance type (if any) |
| `currency` | `string` | Currency (if any) |
| `required` | `decimal string` | Required amount (for INSUFFICIENT_BALANCE) |
| `available` | `decimal string` | Actual available amount (for INSUFFICIENT_BALANCE) |

---

## 6. Publishing Architecture

Shares the Outbox pattern with F-011, but writes to different CF_OUTBOX key prefix:

```
Raft Leader
    │
    │  onStateMachineApply()
    ▼
LedgerStateMachine.apply(command)
    │
    ├── Update in-memory balance (COMPLETED)
    │   or record REJECTED reason
    ├── Write RocksDB WriteBatch:
    │     ├── CF_BALANCE (COMPLETED only)
    │     ├── CF_JOURNAL (COMPLETED only)
    │     ├── CF_OUTBOX key: outbox:balance:...  (F-011, COMPLETED only)
    │     └── CF_OUTBOX key: outbox:completion:requestId  ← This feature
    │
    └── AsyncKafkaPublisher asynchronously sends to two topics
            ├── ledger.balance.change.v1     (F-011)
            └── ledger.posting.completion.v1 (F-011b)
```

**REJECTED also goes through Outbox**: Although REJECTED has no balance change, `PostingCompletionEvent` is still written to CF_OUTBOX to ensure at-least-once delivery to the caller.

---

## 7. Acceptance Criteria

| # | Acceptance Condition | Test Method |
|---|---|---|
| AC-01 | After Posting COMPLETED, `ledger.posting.completion.v1` receives event with result=COMPLETED | Functional Test |
| AC-02 | After Posting REJECTED, event is received with result=REJECTED and errors containing correct errorCode | Functional Test |
| AC-03 | COMPLETED event's legs include all JournalLines, balanceBefore/After consistent with State Machine | Functional Test |
| AC-04 | Multi-leg RFQ Posting, all legs appear in the same completion event | Functional Test |
| AC-05 | Same requestId idempotent retry only emits one completion event | Functional Test |
| AC-06 | Kafka publish failure does not affect Posting result; Outbox redelivers | Failure Injection Test |
| AC-07 | After node restart, unsent completion events in Outbox continue to be delivered | Failure Injection Test |
| AC-08 | REJECTED event's legs is empty array, journalId=null | Functional Test |
| AC-09 | Completion event's occurredAt is consistent with F-011 balanceChangeEvent's occurredAt (same Raft commit) | Functional Test |
| AC-10 | P95 latency (Raft commit to Kafka confirm) ≤ 100ms | Performance Test |

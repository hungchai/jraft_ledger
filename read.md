# Next-Gen Internal Ledger Platform — API Specification

**Version**: 0.2  
**Architecture**: Raft + CQRS + Account-Level Queue (ADR-001)  
**Base Path**: `/ledger`

---

## Architecture Overview

This ledger platform is a high-performance booking engine built on **SOFAJRaft**. The write path goes through the Raft consensus protocol with an in-memory state machine and RocksDB persistence. The read path is split: real-time balance queries hit the in-memory state machine (strong consistency), while journal/history queries use the MySQL view layer synced asynchronously by a Raft Learner (eventual consistency, < 1 second lag).

```
Client → HTTP → Raft Leader → State Machine (in-memory) → RocksDB
                                              ↓
                                         Learner → MySQL (view layer, async)
```

**Key properties**:
- All writes are atomic, committed via Raft quorum
- Balance reads are strongly consistent (in-memory, no DB)
- Journal/history reads are eventually consistent (MySQL view layer)
- Per-account serialized processing eliminates lock contention on hotspot accounts

---

## Table of Contents

1. [Conventions](#1-conventions)
2. [Health](#2-health)
3. [Account Management](#3-account-management)
4. [Balance Type Configuration](#4-balance-type-configuration)
5. [Posting](#5-posting)
6. [Manual Adjustment](#6-manual-adjustment)
7. [Reversal](#7-reversal)
8. [Balance Query](#8-balance-query)
9. [Journal Query](#9-journal-query)
10. [Reconciliation](#10-reconciliation)
11. [Accounting Period](#11-accounting-period)
12. [Kafka Events](#12-kafka-events)
13. [Error Codes](#13-error-codes)
14. [Enumerations](#14-enumerations)

---

## 1. Conventions

### Content-Type
All request and response bodies are `application/json`.

### Idempotency
All write operations require a globally unique `requestId` (UUID v7 recommended). Retrying with the same `requestId` returns the original result without side effects.

### Amounts
All monetary amounts are `decimal` strings in JSON (e.g., `"800000.00"`) to avoid floating-point precision issues. Use `BigDecimal` for parsing.

### Dates & Timestamps
- `valueDate`, `accountingDate` — ISO 8601 date (`"2026-05-16"`)
- `bookedAt`, `occurredAt`, `createdAt`, `expiresAt` — ISO 8601 UTC datetime with nanoseconds (`"2026-05-16T10:30:22.341234567Z"`)

### Data Source Header
Query responses may include `dataSource` to indicate where the data came from:
| Value | Meaning |
|---|---|
| `STATE_MACHINE` | In-memory state machine (strong consistency) |
| `EOD_SNAPSHOT` | MySQL EOD snapshot (eventual) |
| `JOURNAL_REPLAY` | MySQL journal replay (slow, fallback) |
| `VIEW_LAYER` | MySQL view layer (eventual) |

---

## 2. Health

### GET `/health`

Returns service health status.

**Response 200**:
```json
{
  "status": "UP",
  "service": "ledger-platform"
}
```

---

## 3. Account Management

Accounts represent business entities that hold balances. Each account stores metadata and a set of initialized `(balanceType, currency)` pairs.

### 3.1 Create Account

`POST /ledger/accounts`

Creates a new account with initial balance type definitions. **Goes through Raft** for consistency across all nodes.

**Request Body**:

| Field | Type | Required | Description |
|---|---|---|---|
| `requestId` | `string` | Yes | Idempotency key |
| `accountId` | `string` | Yes | Globally unique account ID (caller-assigned) |
| `accountType` | `enum` | Yes | `CLIENT` / `COMPANY` / `SUSPENSE` / `NOSTRO` / `CONTROL` |
| `displayName` | `string` | Yes | Human-readable name |
| `ownerId` | `string` | Conditional | Required for `CLIENT` accounts |
| `balanceInitializations` | `list` | Yes | Initial `(balanceType, currency)` pairs (balance starts at 0) |

```json
{
  "requestId": "req-550e8400-...",
  "accountId": "CLIENT_ACC_001",
  "accountType": "CLIENT",
  "displayName": "Client USD Account",
  "ownerId": "CUST-001",
  "balanceInitializations": [
    { "balanceType": "AVAILABLE_BALANCE", "currency": "USD" },
    { "balanceType": "AVAILABLE_BALANCE", "currency": "HKD" }
  ]
}
```

**Response 200**:
```json
{
  "status": "COMPLETED",
  "journalId": null,
  "errorCodes": []
}
```

**Error Codes**: `ACCOUNT_ALREADY_EXISTS`, `BALANCE_TYPE_NOT_FOUND`, `MISSING_OWNER_ID`

---

### 3.2 Freeze Account

`POST /ledger/accounts/{accountId}/freeze`

Freezes an account. No new postings are accepted for frozen accounts.

**Request Body**:
```json
{
  "requestId": "req-freeze-001"
}
```

**Response 200**:
```json
{
  "status": "COMPLETED",
  "journalId": null,
  "errorCodes": []
}
```

**Error Codes**: `ACCOUNT_NOT_FOUND`, `ACCOUNT_CLOSED`

---

### 3.3 Unfreeze Account

`POST /ledger/accounts/{accountId}/unfreeze`

Unfreezes a previously frozen account.

**Request Body**:
```json
{
  "requestId": "req-unfreeze-001"
}
```

**Response 200**:
```json
{
  "status": "COMPLETED",
  "journalId": null,
  "errorCodes": []
}
```

**Error Codes**: `ACCOUNT_NOT_FOUND`, `ACCOUNT_CLOSED`

---

### 3.4 Close Account

`POST /ledger/accounts/{accountId}/close`

Closes an account permanently. **All balances must be zero.** Once closed, an account cannot be unfrozen or reopened.

**Request Body**:
```json
{
  "requestId": "req-close-001"
}
```

**Response 200**:
```json
{
  "status": "COMPLETED",
  "journalId": null,
  "errorCodes": []
}
```

**Error Codes**: `ACCOUNT_NOT_FOUND`, `ACCOUNT_HAS_NON_ZERO_BALANCE`

---

### 3.5 Add Balance Type to Account

`POST /ledger/accounts/{accountId}/balance-types`

Adds a new `(balanceType, currency)` pair to an existing account. The initial balance is 0. Goes through Raft.

**Request Body**:
```json
{
  "balanceType": "BROKERAGE_BALANCE",
  "currency": "USD",
  "requestId": "req-add-bt-001"
}
```

**Response 200**:
```json
{
  "status": "COMPLETED",
  "journalId": null,
  "errorCodes": []
}
```

**Error Codes**: `ACCOUNT_NOT_FOUND`, `DUPLICATE_BALANCE_TYPE`, `BALANCE_TYPE_NOT_FOUND`

---

### 3.6 Account State Machine

```
        Create
           │
           ▼
       [ACTIVE]
           │
    Freeze │ Unfreeze
           ▼
       [FROZEN]
           │
     Close │ (all balances must = 0)
           ▼
       [CLOSED]  ← terminal, read-only
```

---

## 4. Balance Type Configuration

Balance Types define how balances behave: sign convention, allow-negative semantics, composition rules, visibility, caching, alerts, etc. The system reads configuration from the registry at runtime with no hardcoded types.

> **Note**: This is a system-design specification. The current codebase implements a simplified in-memory `BalanceTypeConfigStore` seeded at startup. The full admin CRUD API (`POST /admin/ledger/balance-types`, etc.) is planned per F-001.

### 4.1 Balance Type Properties

| Property | Type | Description |
|---|---|---|
| `typeCode` | `string` | Globally unique, UPPER_SNAKE_CASE (e.g. `TRADE_AHEAD_BALANCE`) |
| `signConvention` | `enum` | `NORMAL_CREDIT` — credit increases balance / `NORMAL_DEBIT` — debit increases balance |
| `allowNegative` | `boolean` | Whether negative balances are permitted |
| `negativeSemantics` | `enum` | If `allowNegative=true`: `OVERDRAFT` / `SHORT_POSITION` / `PRE_AUTHORIZED` / `CREDIT_UTILIZATION` |
| `zeroFloorEnforce` | `boolean` | Enforce floor at 0 (auto-false when `allowNegative=true`) |
| `compositionLogic` | `enum` | `SUM` — aggregate matching journal lines / `FORMULA` — compute from other balance types |
| `category` | `enum` | `ACTUAL` / `PROJECTED` / `RESERVED` / `SHADOW` |
| `status` | `enum` | `ACTIVE` / `INACTIVE` / `DEPRECATED` |
| `cacheEnabled` | `boolean` | Allow in-memory caching of this balance |
| `cacheTtlSeconds` | `int` | Cache TTL in seconds |

### 4.2 Hard Constraints (Enforced on Every Posting/Adjustment)

```
allowNegative=false → posting must not make balance < 0   → BALANCE_FLOOR_BREACH
allowNegative=true  → posting must not make balance > 0   → BALANCE_CEILING_BREACH
                    (negative-only balances like TRADE_AHEAD_BALANCE)
```

### 4.3 Example: Three Built-in Balance Types

**AVAILABLE_BALANCE** — client-facing, no negatives:
```
signConvention=NORMAL_CREDIT  allowNegative=false
```

> **Note**: The requirements document describes a `FORMULA` composition mode (`CURRENT_BALANCE - HOLD_BALANCE - PENDING_BALANCE`), but this is **not yet implemented**. Currently `AVAILABLE_BALANCE` is treated as an independent, directly-postable bucket.

**TRADE_AHEAD_BALANCE** — debit-direction, always negative-or-zero (pre-authorized):
```
signConvention=NORMAL_DEBIT  allowNegative=true  negativeSemantics=PRE_AUTHORIZED
compositionLogic=SUM
```

**HOLD_BALANCE** — frozen amounts, no negatives, compliance-facing:
```
signConvention=NORMAL_CREDIT  allowNegative=false
compositionLogic=SUM
```

---

## 5. Posting

The core write operation. Accepts one or more legs (each containing debit+credit lines) and atomically books them through Raft. All lines in a request succeed or fail together.

### 5.1 Create Posting

`POST /ledger/postings`

**Request Body**:

| Field | Type | Required | Description |
|---|---|---|---|
| `requestId` | `string` | Yes | Idempotency key |
| `businessEventType` | `string` | Yes | Business event type (e.g., `RFQ_SETTLEMENT`, `WITHDRAWAL`) |
| `businessEventRef` | `string` | Yes | Upstream business event reference |
| `valueDate` | `date` | Yes | Accounting value date |
| `legs` | `list<Leg>` | Yes | At least 1 leg |

**Leg**:
| Field | Type | Required | Description |
|---|---|---|---|
| `legId` | `string` | Yes | Unique leg ID (caller-assigned) |
| `postingType` | `string` | Yes | Posting type (e.g., `TRADE_SETTLEMENT`, `FEE`) |
| `lines` | `list<Line>` | Yes | Journal lines for this leg |

**Line**:
| Field | Type | Required | Description |
|---|---|---|---|
| `accountId` | `string` | Yes | Target account |
| `balanceType` | `string` | Yes | Must exist in registry and be ACTIVE |
| `currency` | `string` | Yes | ISO 4217 currency code |
| `entryType` | `enum` | Yes | `DEBIT` / `CREDIT` |
| `amount` | `decimal` | Yes | Must be > 0 |
| `description` | `string` | No | Line description |

**Example — RFQ Settlement (2 legs, 2 currencies)**:
```json
{
  "requestId": "req-550e8400-e29b-41d4-a716-446655440000",
  "businessEventType": "RFQ_SETTLEMENT",
  "businessEventRef": "RFQ-2026051600123",
  "valueDate": "2026-05-16",
  "legs": [
    {
      "legId": "leg-001",
      "postingType": "TRADE_SETTLEMENT",
      "lines": [
        {
          "accountId": "CLIENT_ACC_001",
          "balanceType": "AVAILABLE_BALANCE",
          "currency": "USD",
          "entryType": "DEBIT",
          "amount": 800000.00,
          "description": "RFQ Client USD sell"
        },
        {
          "accountId": "COMPANY_FX_ACC",
          "balanceType": "AVAILABLE_BALANCE",
          "currency": "USD",
          "entryType": "CREDIT",
          "amount": 800000.00,
          "description": "RFQ Company USD receive"
        }
      ]
    },
    {
      "legId": "leg-002",
      "postingType": "TRADE_SETTLEMENT",
      "lines": [
        {
          "accountId": "COMPANY_FX_ACC",
          "balanceType": "AVAILABLE_BALANCE",
          "currency": "HKD",
          "entryType": "DEBIT",
          "amount": 6240000.00,
          "description": "RFQ Company HKD pay"
        },
        {
          "accountId": "CLIENT_ACC_001",
          "balanceType": "AVAILABLE_BALANCE",
          "currency": "HKD",
          "entryType": "CREDIT",
          "amount": 6240000.00,
          "description": "RFQ Client HKD receive"
        }
      ]
    }
  ]
}
```

**Response 200 — Success**:
```json
{
  "status": "COMPLETED",
  "journalId": "JNL-20260516-000012345",
  "errorCodes": []
}
```

**Response 400 — Rejected**:
```json
{
  "status": "REJECTED",
  "journalId": null,
  "errorCodes": ["INSUFFICIENT_BALANCE"]
}
```

### 5.2 Validation Rules

**Pre-validation** (before Raft):
| Rule | Error Code |
|---|---|
| `requestId` format valid | `INVALID_REQUEST_ID` |
| `legs` not empty | `LEGS_EMPTY` |
| All `balanceType` exist and are ACTIVE | `BALANCE_TYPE_NOT_FOUND` |
| Every `amount` > 0 | `INVALID_AMOUNT` |
| Per-leg DEBIT total = CREDIT total | `JOURNAL_UNBALANCED` |

**Business validation** (in State Machine, reads in-memory balance):
| Rule | Error Code |
|---|---|
| Account exists | `ACCOUNT_NOT_FOUND` |
| `(balanceType, currency)` initialized for account | `BALANCE_NOT_INITIALIZED` |
| `allowNegative=false` and result < 0 | `INSUFFICIENT_BALANCE` |
| `allowNegative=true` and result > 0 | `CREDIT_EXCEEDS_LIMIT` |
| Account not frozen | `ACCOUNT_FROZEN` |

**Idempotency**:
| Condition | Result |
|---|---|
| `requestId` exists and `COMPLETED` | Return original result |
| `requestId` exists and `PROCESSING` | Return `409` (retry) |

### 5.3 Multi-Account Atomicity

For postings spanning multiple accounts (e.g., RFQ with `CLIENT_ACC_001` and `COMPANY_FX_ACC`):

1. Accounts are sorted by ID to prevent deadlock
2. Each account queue is acquired via a coordination token
3. A single `RaftCommand` containing all journal lines is submitted
4. The state machine applies all balance updates atomically
5. Any single validation failure rejects the entire command

### 5.4 Performance Targets

| Metric | Target |
|---|---|
| Posting P95 latency | ≤ 3ms |
| Balance check P95 | ≤ 0.5ms |
| Idempotent retry P95 | ≤ 1ms |
| Hotspot account throughput | Same as normal accounts |

---

## 6. Manual Adjustment

Operator-initiated ledger entries that do not originate from a business event. **All adjustments require Maker-Checker dual approval** (compliance requirement).

### 6.1 Create Draft (Maker)

`POST /ledger/adjustments/drafts`

Creates a draft for review. **Does not post to the ledger** — only validates structure. Does not go through Raft.

**Request Body**:

| Field | Type | Required | Description |
|---|---|---|---|
| `requestId` | `string` | Yes | Idempotency key |
| `makerId` | `string` | Yes | Maker operator ID |
| `legs` | `list<Leg>` | Yes | Same format as Posting (see Section 5) |

```json
{
  "requestId": "draft-req-abc123",
  "makerId": "ops-user-001",
  "legs": [
    {
      "legId": "leg-001",
      "postingType": "INTEREST_ADJUSTMENT",
      "lines": [
        {
          "accountId": "CLIENT_ACC_001",
          "balanceType": "AVAILABLE_BALANCE",
          "currency": "USD",
          "entryType": "CREDIT",
          "amount": 150.00,
          "description": "Interest correction"
        },
        {
          "accountId": "COMPANY_FX_ACC",
          "balanceType": "AVAILABLE_BALANCE",
          "currency": "USD",
          "entryType": "DEBIT",
          "amount": 150.00,
          "description": "Interest correction"
        }
      ]
    }
  ]
}
```

**Response 200**:
```json
{
  "draftId": "ADJ-DRAFT-20260516-000001",
  "command": { "... (the posting command) ..." },
  "makerId": "ops-user-001",
  "status": "PENDING_APPROVAL",
  "createdAt": "2026-05-16T14:30:00.000Z",
  "expiresAt": "2026-05-17T14:30:00.000Z"
}
```

**Validation at draft creation** (structural only, no balance check):
| Rule | Error Code |
|---|---|
| Legs valid, debit = credit | `JOURNAL_UNBALANCED` |
| All account IDs exist and are ACTIVE | `ACCOUNT_NOT_FOUND` |
| All balance types exist in registry | `BALANCE_TYPE_NOT_FOUND` |

---

### 6.2 Approve Draft (Checker)

`POST /ledger/adjustments/drafts/{draftId}/approve`

Approves the draft and posts it to the ledger. **Goes through Raft.** Balance validation happens at this point.

**Request Body**:
```json
{
  "checkerId": "ops-user-002",
  "approveRequestId": "approve-req-xyz789"
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `checkerId` | `string` | Yes | Must differ from `makerId` |
| `approveRequestId` | `string` | Yes | Idempotency key for the approval |

**Response 200**:
```json
{
  "status": "COMPLETED",
  "journalId": "JNL-20260516-000012399",
  "errorCodes": []
}
```

**Validation at approval**:
| Rule | Error Code |
|---|---|
| `checkerId` ≠ `makerId` | `MAKER_CHECKER_SAME_PERSON` |
| Draft not expired | `DRAFT_EXPIRED` |
| Draft status = `PENDING_APPROVAL` | `DRAFT_NOT_PENDING` |
| Balance checks (same as Section 5.2) | `INSUFFICIENT_BALANCE`, etc. |

---

### 6.3 Reject Draft (Checker)

`POST /ledger/adjustments/drafts/{draftId}/reject`

Rejects the draft. No posting occurs.

**Request Body**:
```json
{
  "checkerId": "ops-user-002",
  "reason": "Wrong amount, please resubmit"
}
```

**Response 200**:
```json
{
  "status": "REJECTED"
}
```

---

### 6.4 Draft State Machine

```
Maker submits draft
       │
       ▼
 [PENDING_APPROVAL]
    │       │       │
 Approve  Reject  24h timeout
    │       │       │
    ▼       ▼       ▼
[EXECUTED] [REJECTED] [EXPIRED]
```

- Drafts expire after 24 hours (configurable)
- Expired/REJECTED/EXECUTED drafts cannot be approved

---

## 7. Reversal

Reverses a previously booked journal by creating a mirror journal with all DEBIT/CREDIT entries swapped. This is the only way to "undo" a posting — journals are append-only and never modified or deleted.

### 7.1 Reverse a Journal

`POST /ledger/journals/{journalId}/reversal`

**Request Body**:

| Field | Type | Required | Description |
|---|---|---|---|
| `requestId` | `string` | Yes | Idempotency key |
| `reversalReason` | `string` | Yes | Free-text reason (max 500 chars) |
| `reversalReasonCode` | `enum` | Yes | Standard reason code (see below) |
| `valueDate` | `date` | Yes | Value date for the reversal |

**Reason Codes**: `TRADE_CANCELLED` / `WRONG_AMOUNT` / `WRONG_ACCOUNT` / `WRONG_CURRENCY` / `SYSTEM_ERROR` / `RECONCILIATION_ADJUSTMENT` / `COMPLIANCE_REQUIREMENT` / `OTHER`

```json
{
  "requestId": "rev-req-7f3a9b2c-...",
  "reversalReason": "RFQ trade cancelled by client",
  "reversalReasonCode": "TRADE_CANCELLED",
  "valueDate": "2026-05-16"
}
```

**Response 200 — Success**:
```json
{
  "status": "COMPLETED",
  "journalId": "JNL-20260516-000012346",
  "errorCodes": []
}
```

**Response 400 — Rejected**:
```json
{
  "status": "REJECTED",
  "journalId": null,
  "errorCodes": ["JOURNAL_ALREADY_REVERSED"]
}
```

### 7.2 Constraints

| Constraint | Error Code |
|---|---|
| Only reverse `CONFIRMED` journals | `JOURNAL_ALREADY_REVERSED` |
| Cannot reverse a REVERSAL journal | `CANNOT_REVERSE_REVERSAL` |
| Full reversal only — all lines are reversed | (structural guarantee) |
| Reversal does NOT check balance sufficiency — it always executes | — |
| Cross-period reversals get `crossPeriod=true` | (metadata flag) |

### 7.3 What Happens During Reversal

1. Original journal status changes: `CONFIRMED` → `REVERSED`
2. A new `REVERSAL` journal is created with all entries mirrored
3. All affected account balances are atomically updated (rolled back)
4. New `BalanceChangeEvent` and `PostingCompletionEvent` are emitted

### 7.4 Rebook Pattern

To correct and rebook after a reversal:
```
Step 1: POST /ledger/journals/{wrongJournalId}/reversal    → gets reversalJournalId
Step 2: POST /ledger/postings                              → submits corrected posting
```

---

## 8. Balance Query

Real-time balance reads hit the in-memory state machine on the Raft Leader (strong consistency). Historical ("as-of") queries hit the MySQL view layer.

### 8.1 Get Current Balance

`GET /ledger/balances?accountId={id}&balanceType={type}&currency={ccy}`

**Parameters**:

| Parameter | Description |
|---|---|
| `accountId` | Account ID |
| `balanceType` | Balance type code |
| `currency` | ISO 4217 currency |

**Response 200**:
```json
{
  "accountId": "CLIENT_ACC_001",
  "balanceType": "AVAILABLE_BALANCE",
  "currency": "USD",
  "amount": 200000.00,
  "allowNegative": false,
  "dataSource": "STATE_MACHINE"
}
```

### 8.2 Get Balance As-Of Date

`GET /ledger/balances/as-of?accountId={id}&balanceType={type}&currency={ccy}&asOf={date}`

Returns the historical balance as of a specific date. Reads from EOD snapshots or replays journals from MySQL.

**Response 200**:
```json
{
  "accountId": "CLIENT_ACC_001",
  "balanceType": "AVAILABLE_BALANCE",
  "currency": "USD",
  "amount": 1000000.00,
  "allowNegative": false,
  "dataSource": "EOD_SNAPSHOT"
}
```

### 8.3 Batch Balance Query

`POST /ledger/balances/batch`

Queries multiple balances in a single request.

**Request Body**:
```json
[
  {
    "accountId": "CLIENT_ACC_001",
    "balanceType": "AVAILABLE_BALANCE",
    "currency": "USD"
  },
  {
    "accountId": "COMPANY_FX_ACC",
    "balanceType": "AVAILABLE_BALANCE",
    "currency": "USD"
  }
]
```

**Response 200**:
```json
[
  {
    "accountId": "CLIENT_ACC_001",
    "balanceType": "AVAILABLE_BALANCE",
    "currency": "USD",
    "amount": 200000.00,
    "allowNegative": false,
    "dataSource": "STATE_MACHINE"
  },
  {
    "accountId": "COMPANY_FX_ACC",
    "balanceType": "AVAILABLE_BALANCE",
    "currency": "USD",
    "amount": 5800000.00,
    "allowNegative": false,
    "dataSource": "STATE_MACHINE"
  }
]
```

### 8.4 Data Sources (Balance Reads)

| Source | Consistency | Latency (P95) | When |
|---|---|---|---|
| `STATE_MACHINE` | Strong | ≤ 2ms | Real-time queries (default) |
| `EOD_SNAPSHOT` | Eventual | ≤ 30ms | As-of queries within snapshot range |
| `JOURNAL_REPLAY` | Eventual | ≤ 5s | Fallback when no snapshot exists |

### 8.5 Performance Targets

| Query Type | P95 Target |
|---|---|
| Single balance (Active account) | ≤ 2ms |
| Single balance (Inactive account, cold load from RocksDB) | ≤ 5ms |
| Batch 200 accounts | ≤ 10ms |

---

## 9. Journal Query

Query journals and journal lines. Reads the MySQL view layer (eventual consistency, typically < 1 second behind the state machine).

### 9.1 Get Journal by ID

`GET /ledger/journals/{journalId}`

Returns the full journal with all its lines.

**Response 200**:
```json
{
  "journalId": "JNL-20260516-000012345",
  "journalType": "NORMAL",
  "requestId": "req-550e8400-...",
  "businessEventType": "RFQ_SETTLEMENT",
  "businessEventRef": "RFQ-2026051600123",
  "valueDate": "2026-05-16",
  "status": "CONFIRMED",
  "crossPeriod": false,
  "createdAt": "2026-05-16T10:30:22.341Z",
  "lines": [
    {
      "journalLineId": "JL-000024689",
      "accountId": "CLIENT_ACC_001",
      "balanceType": "AVAILABLE_BALANCE",
      "currency": "USD",
      "entryType": "DEBIT",
      "amount": 800000.00,
      "balanceBefore": 1000000.00,
      "balanceAfter": 200000.00
    }
  ]
}
```

**Response 404** — journal not found.

---

### 9.2 Get Journals by Account

`GET /ledger/journals?accountId={id}&page={n}&size={n}`

Paginated journal listing for a specific account.

**Parameters**:

| Parameter | Default | Description |
|---|---|---|
| `accountId` | (required) | Account ID |
| `page` | `0` | Page number (0-indexed) |
| `size` | `50` | Page size |

**Response 200** — paginated list of journals.

---

### 9.3 Get Journals by Business Event Reference

`GET /ledger/journals/by-business-ref?businessEventRef={ref}`

Returns all journals related to a business event, including original postings, reversals, and rebooks.

---

### 9.4 Get Journal by Request ID

`GET /ledger/journals/by-request-id?requestId={id}`

Used by upstream systems to check whether a posting was successfully booked.

**Response 200** — the journal, or **404** if not found.

---

### 9.5 Get Journal Chain

`GET /ledger/journals/chain/{journalId}`

Returns the full lineage of a journal: original → reversal → rebook.

**Response 200**:
```json
[
  {
    "journalId": "JNL-...12345",
    "journalType": "NORMAL",
    "status": "REVERSED",
    "businessEventRef": "RFQ-2026051600123"
  },
  {
    "journalId": "JNL-...12346",
    "journalType": "REVERSAL",
    "status": "CONFIRMED"
  },
  {
    "journalId": "JNL-...12399",
    "journalType": "NORMAL",
    "status": "CONFIRMED",
    "businessEventRef": "RFQ-2026051600123-REBOOK"
  }
]
```

### 9.6 Performance Targets

| Query Type | P95 Target |
|---|---|
| Single journal by ID | ≤ 10ms |
| Account journals (50 items) | ≤ 30ms |
| Business event trace | ≤ 50ms |
| Full chain trace | ≤ 100ms |

---

## 10. Reconciliation

Three-level reconciliation framework: L1 (internal consistency), L2 (sub-ledger vs. control), L3 (external settlement matching).

### 10.1 L1 — Internal Journal Reconciliation

`POST /ledger/reconciliation/l1`

Verifies that all journals are balanced (debit = credit) and that the state machine matches the MySQL view layer.

**Request Body**:
```json
{
  "date": "2026-05-16"
}
```

**Response 200**:
```json
{
  "message": "L1 reconciliation endpoint ready"
}
```

> Note: Full L1 implementation (journal sweep, balance consistency check) is planned.

---

### 10.2 L2 — Sub-Ledger vs. Control Account

`POST /ledger/reconciliation/l2`

Verifies that the sum of all client account balances matches the company control account.

**Request Body**:

| Field | Type | Description |
|---|---|---|
| `date` | `string` | Reconciliation date |
| `accountBalances` | `map<string, number>` | Map of sub-account balances |
| `controlAccountId` | `string` | The control account to verify against |
| `controlBalance` | `decimal` | Expected control account balance |
| `tolerance` | `decimal` | Allowed difference (default `0.01`) |

```json
{
  "date": "2026-05-16",
  "accountBalances": {
    "CLIENT_ACC_001": 200000.00,
    "CLIENT_ACC_002": 150000.00
  },
  "controlAccountId": "CONTROL_CLIENT_USD",
  "controlBalance": 350000.00,
  "tolerance": 0.01
}
```

**Response 200**:
```json
{
  "date": "2026-05-16",
  "l1Summary": null,
  "l2Summary": {
    "rulesPassed": 1,
    "rulesFailed": 0
  },
  "l3Summary": null,
  "cases": []
}
```

---

### 10.3 L3 — External Settlement Reconciliation

`POST /ledger/reconciliation/l3`

Matches internal journals against external settlement records.

**Request Body**:

| Field | Type | Description |
|---|---|---|
| `date` | `string` | Reconciliation date |
| `externalRecords` | `list` | External records with `externalRef` and `amount` |
| `journalIds` | `list<string>` | Internal journal IDs to match against |

```json
{
  "date": "2026-05-16",
  "externalRecords": [
    { "externalRef": "EXT-001", "amount": 800000.00 },
    { "externalRef": "EXT-002", "amount": 6240000.00 }
  ],
  "journalIds": [
    "JNL-20260516-000012345",
    "JNL-20260516-000012346"
  ]
}
```

**Response 200** — a `ReconciliationReport` with L3 summary and cases.

### 10.4 Discrepancy Classification (L3)

| Category | Meaning |
|---|---|
| `MATCHED` | Internal and external agree |
| `INTERNAL_ONLY` | In ledger but not in external file |
| `EXTERNAL_ONLY` | In external file but not in ledger |
| `AMOUNT_MISMATCH` | Both exist but amounts differ |

---

## 11. Accounting Period

Manages day-boundary cut-off for accounting periods and triggers EOD (End of Day) processing.

### 11.1 Trigger EOD

`POST /ledger/periods/eod`

Closes the current accounting period and triggers end-of-day tasks.

**Request Body**:
```json
{
  "date": "2026-05-16"
}
```

**Response 200**:
```json
{
  "date": "2026-05-16",
  "status": "CLOSED"
}
```

### 11.2 Get Period Status

`GET /ledger/periods?date={date}`

**Response 200**:
```json
{
  "periodId": "2026-05-16",
  "date": "2026-05-16",
  "status": "CLOSED"
}
```

### 11.3 Period State Machine

```
       [OPEN]          ← normal posting
          │
    EOD trigger
          │
          ▼
      [CLOSING]        ← no new postings; drain in-flight
          │
    EOD tasks done
          │
          ▼
      [CLOSED]         ← terminal for this period; cross-period reversals allowed
```

### 11.4 Cross-Period Rules

| Action | Rule |
|---|---|
| Post to closed period | Rejected (`PERIOD_CLOSED`) |
| Reverse journal in closed period | Allowed, tagged `crossPeriod=true` |
| Manual adjustment in closed period | Allowed, requires additional approval |

---

## 12. Kafka Events

The platform emits two types of Kafka events. Both use the **Outbox pattern** (written atomically with the RocksDB write batch, then published asynchronously), ensuring at-least-once delivery.

### 12.1 BalanceChangeEvent

**Topic**: `ledger.balance.change.v1`  
**Partition key**: `accountId:balanceType:currency`  
**Granularity**: One event per journal line (per balance change)  
**Consumers**: Risk Engine, VAMP, downstream streaming

**Event Schema (v1.1)**:

| Field | Type | Description |
|---|---|---|
| `eventId` | `string` | Unique event ID (UUID v7) |
| `eventType` | `string` | Always `BALANCE_CHANGE` |
| `eventVersion` | `string` | `1.1` |
| `occurredAt` | `timestamp` | Raft commit time (nanosecond precision) |
| `idempotencyKey` | `string` | `requestId:accountId:balanceType:currency` |
| `commandType` | `enum` | `POSTING` / `REVERSAL` / `MANUAL_ADJUSTMENT` |
| `journalId` | `string` | Journal ID |
| `journalLineId` | `string` | Journal line ID |
| `requestId` | `string` | Original request idempotency key |
| `businessEventRef` | `string` | Business event reference |
| `traceId` | `string` | Distributed trace ID |
| `accountId` | `string` | Account ID |
| `balanceType` | `string` | Balance type code |
| `currency` | `string` | ISO 4217 currency |
| `entryType` | `enum` | `DEBIT` / `CREDIT` |
| `amount` | `decimal string` | Change amount (positive) |
| `preBalance` | `decimal string` | Balance before change |
| `postBalance` | `decimal string` | Balance after change |
| `balanceDelta` | `decimal string` | Net change (negative = DEBIT, positive = CREDIT) |
| `raftLogIndex` | `long` | Global Raft log index |
| `stateVersion` | `long` | State machine version (= raftLogIndex) |
| `accountSeq` | `long` | Per-account monotonically increasing sequence (v1.1) |
| `prevAccountSeq` | `long` | Previous sequence in the same account/type/currency dimension (v1.1) |
| `accountingDate` | `date` | Accounting period date |
| `valueDate` | `date` | Value date |
| `metadata` | `map` | Extension fields |

**Example (DEBIT)**:
```json
{
  "eventId": "evt-01JWXYZ123456789ABCDEF",
  "eventType": "BALANCE_CHANGE",
  "eventVersion": "1.1",
  "occurredAt": "2026-05-17T23:30:00.123456789Z",
  "idempotencyKey": "req-001:CLIENT_ACC_001:AVAILABLE_BALANCE:USD",
  "commandType": "POSTING",
  "journalId": "JNL-01JWXYZ000000001",
  "journalLineId": "JLL-01JWXYZ000000001-01",
  "requestId": "req-01JWXYZ000000001",
  "businessEventRef": "RFQ-20260517-001",
  "traceId": "trace-abc123",
  "accountId": "CLIENT_ACC_001",
  "balanceType": "AVAILABLE_BALANCE",
  "currency": "USD",
  "entryType": "DEBIT",
  "amount": "800.00",
  "preBalance": "1000.00",
  "postBalance": "200.00",
  "balanceDelta": "-800.00",
  "raftLogIndex": 100245,
  "stateVersion": 100245,
  "accountSeq": 1042,
  "prevAccountSeq": 1041,
  "accountingDate": "2026-05-17",
  "valueDate": "2026-05-17",
  "metadata": {
    "operatorId": "system",
    "sourceSystem": "LEDGER"
  }
}
```

### 12.2 PostingCompletionEvent

**Topic**: `ledger.posting.completion.v1`  
**Partition key**: `requestId`  
**Granularity**: One event per request (COMPLETED or REJECTED)  
**Consumers**: Upstream business systems (RFQ Engine, Order Management)

**Event Schema (v1.0) — COMPLETED**:

| Field | Type | Description |
|---|---|---|
| `eventId` | `string` | Unique event ID |
| `eventType` | `string` | Always `POSTING_COMPLETION` |
| `eventVersion` | `string` | `1.0` |
| `occurredAt` | `timestamp` | Raft commit time |
| `idempotencyKey` | `string` | Same as `requestId` |
| `requestId` | `string` | Original request ID |
| `commandType` | `enum` | `POSTING` / `REVERSAL` / `MANUAL_ADJUSTMENT` |
| `businessEventType` | `string` | Business event type |
| `businessEventRef` | `string` | Business event reference |
| `result` | `enum` | `COMPLETED` / `REJECTED` |
| `journalId` | `string` | Journal ID (null if REJECTED) |
| `bookedAt` | `timestamp` | Raft commit time (null if REJECTED) |
| `raftLogIndex` | `long` | Raft log index (null if REJECTED) |
| `legs` | `list` | Full leg/line details with `balanceBefore`/`balanceAfter` (empty if REJECTED) |
| `errors` | `list` | Error details (null if COMPLETED) |
| `traceId` | `string` | Distributed trace ID |
| `metadata` | `map` | Extension fields |

**Event Schema — REJECTED**:
```json
{
  "eventId": "evt-01JWXYZ999888777FEDCBA",
  "eventType": "POSTING_COMPLETION",
  "eventVersion": "1.0",
  "occurredAt": "2026-05-17T23:30:01.000Z",
  "idempotencyKey": "req-rejected-001",
  "requestId": "req-rejected-001",
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

### 12.3 Delivery Guarantees

| Guarantee | Mechanism |
|---|---|
| No loss | Outbox in same RocksDB WriteBatch as balance update |
| At-least-once | Outbox retry with exponential backoff (max 10 retries) |
| Non-blocking | Async publish; Kafka failure does not affect posting response |
| Per-account ordering | Partition key = `accountId:balanceType:currency` (BalanceChangeEvent) |
| Per-request ordering | Partition key = `requestId` (PostingCompletionEvent) |
| Gap detection | `accountSeq`/`prevAccountSeq` fields (BalanceChangeEvent v1.1) |

---

## 13. Error Codes

### Validation Errors
| Code | HTTP | Description |
|---|---|---|
| `INVALID_REQUEST_ID` | 400 | requestId format invalid |
| `LEGS_EMPTY` | 400 | No legs provided |
| `INVALID_AMOUNT` | 400 | Amount must be > 0 |
| `JOURNAL_UNBALANCED` | 400 | DEBIT total ≠ CREDIT total per leg |
| `INVALID_JOURNAL_ID` | 400 | journalId format invalid |
| `INVALID_REASON_CODE` | 400 | Reversal reason code not in enum |
| `MISSING_OPERATOR` | 400 | operatorId is required |
| `CHANGE_REASON_REQUIRED` | 400 | Balance type config change requires reason |

### Account Errors
| Code | HTTP | Description |
|---|---|---|
| `ACCOUNT_ALREADY_EXISTS` | 409 | accountId is taken |
| `ACCOUNT_NOT_FOUND` | 404 | Account does not exist |
| `ACCOUNT_FROZEN` | 422 | Account is frozen |
| `ACCOUNT_CLOSED` | 422 | Account is closed |
| `ACCOUNT_HAS_NON_ZERO_BALANCE` | 422 | Cannot close account with non-zero balance |
| `MISSING_OWNER_ID` | 400 | CLIENT accounts require ownerId |

### Balance Errors
| Code | HTTP | Description |
|---|---|---|
| `BALANCE_TYPE_NOT_FOUND` | 404 | Balance type not in registry |
| `BALANCE_TYPE_INACTIVE` | 422 | Balance type is not ACTIVE |
| `BALANCE_NOT_INITIALIZED` | 422 | (balanceType, currency) not set up for this account |
| `DUPLICATE_BALANCE_TYPE` | 409 | Balance type already exists on account |
| `INSUFFICIENT_BALANCE` | 422 | Posting would make `allowNegative=false` balance negative |
| `CREDIT_EXCEEDS_LIMIT` | 422 | Posting would make `allowNegative=true` balance positive |
| `BALANCE_FLOOR_BREACH` | 422 | Same as INSUFFICIENT_BALANCE |
| `BALANCE_CEILING_BREACH` | 422 | Same as CREDIT_EXCEEDS_LIMIT |

### Journal / Reversal Errors
| Code | HTTP | Description |
|---|---|---|
| `JOURNAL_NOT_FOUND` | 404 | Journal does not exist |
| `JOURNAL_ALREADY_REVERSED` | 422 | Journal status is not CONFIRMED |
| `CANNOT_REVERSE_REVERSAL` | 422 | Cannot reverse a REVERSAL journal |

### Adjustment / Draft Errors
| Code | HTTP | Description |
|---|---|---|
| `MAKER_CHECKER_SAME_PERSON` | 422 | Checker must differ from maker |
| `DRAFT_EXPIRED` | 422 | Draft past expiry |
| `DRAFT_NOT_PENDING` | 422 | Draft already approved/rejected/expired |
| `INVALID_ADJUSTMENT_TYPE` | 400 | Adjustment type not in enum |

### Period Errors
| Code | HTTP | Description |
|---|---|---|
| `PERIOD_CLOSED` | 422 | Cannot post to a closed accounting period |

### Idempotency / Concurrency
| Code | HTTP | Description |
|---|---|---|
| `PROCESSING` | 409 | Request is being processed; retry |

---

## 14. Enumerations

### AccountType
| Value | Description |
|---|---|
| `CLIENT` | Client/customer account |
| `COMPANY` | Company-owned account (including RFQ counterparty) |
| `SUSPENSE` | Suspense account for settlement differences |
| `NOSTRO` | Our account at a counterparty bank |
| `CONTROL` | Control account for L2 reconciliation |

### AccountStatus
| Value | Description |
|---|---|
| `ACTIVE` | Normal, accepts postings |
| `FROZEN` | No new postings accepted |
| `CLOSED` | Terminal, read-only |

### EntryType
| Value | Description |
|---|---|
| `DEBIT` | Debit entry |
| `CREDIT` | Credit entry |

### JournalType
| Value | Description |
|---|---|
| `NORMAL` | Standard business posting |
| `REVERSAL` | Reversal journal |
| `MANUAL_ADJUSTMENT` | Manual adjustment journal |

### JournalStatus
| Value | Description |
|---|---|
| `CONFIRMED` | Successfully booked |
| `REVERSED` | Has been reversed |

### DraftStatus
| Value | Description |
|---|---|
| `PENDING_APPROVAL` | Awaiting checker review |
| `EXECUTED` | Approved and posted |
| `REJECTED` | Checker rejected |
| `EXPIRED` | Timed out |

### PeriodStatus
| Value | Description |
|---|---|
| `OPEN` | Accepting postings |
| `CLOSING` | Draining in-flight, no new postings |
| `CLOSED` | Terminal for this period |

### SignConvention
| Value | Description |
|---|---|
| `NORMAL_CREDIT` | Credit increases balance (standard) |
| `NORMAL_DEBIT` | Debit increases balance (reversed) |

### NegativeSemantics
| Value | Description |
|---|---|
| `OVERDRAFT` | Overdraft |
| `SHORT_POSITION` | Short position |
| `PRE_AUTHORIZED` | Pre-authorized hold (e.g., TRADE_AHEAD) |
| `CREDIT_UTILIZATION` | Credit utilization |

### ReversalReasonCode
| Value | Description |
|---|---|
| `TRADE_CANCELLED` | Trade was cancelled |
| `WRONG_AMOUNT` | Incorrect amount |
| `WRONG_ACCOUNT` | Incorrect account |
| `WRONG_CURRENCY` | Incorrect currency |
| `SYSTEM_ERROR` | System error |
| `RECONCILIATION_ADJUSTMENT` | Reconciliation adjustment |
| `COMPLIANCE_REQUIREMENT` | Compliance requirement |
| `OTHER` | Other (provide reason text) |

### CommandResult Status
| Value | Description |
|---|---|
| `COMPLETED` | Operation succeeded |
| `REJECTED` | Operation rejected |

### Kafka CommandType (in events)
| Value | Description |
|---|---|
| `POSTING` | Standard posting |
| `REVERSAL` | Reversal |
| `MANUAL_ADJUSTMENT` | Manual adjustment |

---

## Appendix A: Request Flow Summary

```
POST /ledger/postings
       │
       ▼
  Request Queue → Pre-validation (schema, auth)
       │
       ▼
  Account Queue Coordinator (sort accounts, acquire tokens)
       │
       ▼
  Account Worker (idempotency check, balance validation via in-memory state machine)
       │
       ▼
  Raft Leader → replicate to Followers → Quorum commit
       │
       ▼
  State Machine Apply:
    ├─ Generate Journal + JournalLines
    ├─ Update in-memory balances (with accountSeq increment)
    ├─ RocksDB WriteBatch (journal + balance + outbox events)
    └─ Update idempotency store
       │
       ▼
  Response to client + Async Kafka publish (Outbox)
       │
       ▼
  Learner → async sync to MySQL view layer
```

## Appendix B: Data Storage

| Store | Role | Consistency |
|---|---|---|
| **In-Memory State Machine** | Current balances, idempotency, account metadata, balance type config | Strong (Leader only) |
| **RocksDB** | Source of truth: journals, journal lines, balances (with accountSeq), idempotency, outbox events | Strong (Raft WAL) |
| **MySQL** | View layer: journals, journal lines, account balances, EOD snapshots, reconciliation reports, adjustment drafts | Eventual (< 1s lag) |
| **Kafka** | Events: BalanceChangeEvent, PostingCompletionEvent | At-least-once (Outbox) |
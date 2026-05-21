# F-004 Reversal — Functional Requirements Specification

**Document Version**: v0.1  
**Feature**: F-004 Reversal (Reverse an existing Journal)  
**System**: Next-Gen Internal Ledger Platform  
**Status**: Draft for Review  
**Dependencies**: ADR-001, F-002 Posting API, F-008 State Machine Design

---

## 1. Feature Overview

Reversal is a full offset of a posted Journal. It generates a mirror Journal where all JournalLine DEBIT / CREDIT entries are swapped, with the same amounts, so that the net effect of the two Journals is zero.

**Core Principles**:
- Posted journal entries **cannot be modified or deleted**; only Reversal is allowed
- A Reversal itself is also a Journal, equally append-only and immutable
- If re-booking is needed after Reversal, a new Posting request must be submitted (Rebook)

---

## 2. Applicable Scenarios

| Scenario | Description |
|---|---|
| Trade Cancellation | After RFQ execution, the client or system cancels and the posted entry must be reversed |
| Incorrect Posting | Posting used wrong amount, currency, or account; must Reverse then Rebook |
| System Reconciliation Difference | External settlement result differs from internal ledger; reverse and re-reconcile |
| End-of-Day Adjustment | Error discovered before accounting period closes; same-day reversal required |

---

## 3. Constraints

| # | Constraint | Description |
|---|---|---|
| C-01 | Can only Reverse Journals with `status = CONFIRMED` | Already Reversed Journals cannot be Reversed again |
| C-02 | Partial Reversal is not allowed | Must reverse all JournalLines of the entire Journal; reversing a single leg is not permitted |
| C-03 | Reversal does not check balance sufficiency | Reversal is an offset operation and must succeed; if the balance is insufficient (e.g. funds already used), the system still executes the Reversal, allowing the balance to become correspondingly negative (handled by subsequent processes) |
| C-04 | A Reversal itself cannot be Reversed | Prevents infinite reversal chains |
| C-05 | Cross-period Reversal must be flagged | If the original Journal's valueDate falls in a closed accounting period, mark `crossPeriod=true` for the reporting system to handle |

---

## 4. Request Structure

### 4.1 API

```
POST /ledger/journals/{originalJournalId}/reversal
```

### 4.2 Request Body

| Field | Type | Required | Description |
|---|---|---|---|
| `requestId` | `string` | ✅ | Idempotency key, globally unique (UUID v7) |
| `reversalReason` | `string` | ✅ | Reversal reason, free text (max 500 characters) |
| `reversalReasonCode` | `enum` | ✅ | Reason code; see table below |
| `valueDate` | `date` | ✅ | Reversal ledger effective date (may differ from the original Journal) |
| `operatorId` | `string` | ✅ | Operator ID, for audit purposes |
| `approvalRef` | `string` | ❌ | Approval reference number (if the system requires maker-checker) |
| `metadata` | `map<string,string>` | ❌ | Extension fields |

### 4.3 reversalReasonCode Enumeration

| Code | Description |
|---|---|
| `TRADE_CANCELLED` | Trade cancelled |
| `WRONG_AMOUNT` | Wrong amount |
| `WRONG_ACCOUNT` | Wrong account |
| `WRONG_CURRENCY` | Wrong currency |
| `SYSTEM_ERROR` | System error |
| `RECONCILIATION_ADJUSTMENT` | Reconciliation adjustment |
| `COMPLIANCE_REQUIREMENT` | Compliance requirement |
| `OTHER` | Other (requires reversalReason description) |

### 4.4 Request Example

```json
{
  "requestId": "rev-req-7f3a9b2c-1234-5678-abcd-ef0123456789",
  "reversalReason": "RFQ trade cancelled by client, original trade RFQ-2026051600123",
  "reversalReasonCode": "TRADE_CANCELLED",
  "valueDate": "2026-05-16",
  "operatorId": "ops-user-001",
  "approvalRef": "APPR-2026051600456"
}
```

---

## 5. Validation Rules

### 5.1 Pre-Validation (Network Layer)

| # | Rule | Error Code |
|---|---|---|
| V-01 | `requestId` format is valid | `INVALID_REQUEST_ID` |
| V-02 | `originalJournalId` format is valid | `INVALID_JOURNAL_ID` |
| V-03 | `reversalReasonCode` is within the enumerated range | `INVALID_REASON_CODE` |
| V-04 | `operatorId` is not empty | `MISSING_OPERATOR` |

### 5.2 Business Validation (State Machine, in-memory)

| # | Rule | Error Code |
|---|---|---|
| V-05 | The Journal corresponding to `originalJournalId` exists | `JOURNAL_NOT_FOUND` |
| V-06 | Original Journal `status = CONFIRMED` | `JOURNAL_ALREADY_REVERSED` |
| V-07 | Original Journal `journalType ≠ REVERSAL` | `CANNOT_REVERSE_REVERSAL` |
| V-08 | Idempotency: if `requestId` already exists, return the original result directly | — |

### 5.3 Cross-Period Flagging

| # | Rule | Handling |
|---|---|---|
| V-09 | If the original Journal's `valueDate` falls in a closed accounting period | Mark `crossPeriod=true`; Reversal is still allowed, but the reporting system is notified |

---

## 6. Execution Flow (Raft Write Path)

```
1. [Network Layer]
   Receive POST request → Pre-validation (V-01 ~ V-04)
   → Enqueue into request_queue

2. [Ledger Layer]
   Dequeue from request_queue
   → Read all involved accountIds of the original Journal from the State Machine
   → Sort by accountId in ascending order
   → Route to respective Account Queues (Multi-Account Coordinator)

3. [Account Queue Coordinator]
   Wait for all involved account queues to be ready
   → Idempotency check (V-08)
   → Business validation (V-05 ~ V-09)
   → Build REVERSAL_CMD

4. [Raft Layer]
   Submit REVERSAL_CMD → Leader replicates to Followers → Quorum commit

5. [State Machine Apply]
   a. Generate Reversal Journal:
      journalType = REVERSAL
      originalJournalId = {originalJournalId}
      status = CONFIRMED
      crossPeriod = true/false

   b. Generate mirror JournalLines (reverse each line):
      Original DEBIT → CREDIT
      Original CREDIT → DEBIT
      Amount unchanged
      balanceBefore / balanceAfter calculated based on current State Machine

   c. Update original Journal status:
      original Journal.status = REVERSED
      original Journal.reversalJournalId = {new Reversal journalId}

   d. Atomically update in-memory balances for all involved accounts

   e. Atomic WriteBatch to RocksDB:
      - New Reversal Journal
      - All new JournalLines
      - Updated original Journal (status + reversalJournalId)
      - Updated balances

6. [Learner Async Sync]
   Learner syncs the Reversal Journal and the updated original Journal to MySQL View Layer

7. [Response]
   Return Reversal result
```

---

## 7. Journal State Machine in the State Machine

```
         Posting
            │
            ▼
       [CONFIRMED]  ←─── Status after normal posting
            │
            │ Reversal
            ▼
       [REVERSED]   ←─── Cannot be Reversed again

       [REVERSAL]   ←─── Reversal Journal's own journalType; status remains CONFIRMED
                         Cannot be Reversed (enforced by V-07 validation)
```

---

## 8. Response Structure

### 8.1 Success Response (HTTP 200)

```json
{
  "requestId": "rev-req-7f3a9b2c-1234-5678-abcd-ef0123456789",
  "status": "COMPLETED",
  "reversalJournalId": "JNL-20260516-000012346",
  "originalJournalId": "JNL-20260516-000012345",
  "crossPeriod": false,
  "bookedAt": "2026-05-16T14:22:11.500Z",
  "legs": [
    {
      "lines": [
        {
          "journalLineId": "JL-000024693",
          "accountId": "CLIENT_ACC_001",
          "balanceType": "AVAILABLE_BALANCE",
          "currency": "USD",
          "entryType": "CREDIT",
          "amount": 800000.00,
          "balanceBefore": 200000.00,
          "balanceAfter": 1000000.00
        },
        {
          "journalLineId": "JL-000024694",
          "accountId": "COMPANY_FX_ACC",
          "balanceType": "AVAILABLE_BALANCE",
          "currency": "USD",
          "entryType": "DEBIT",
          "amount": 800000.00,
          "balanceBefore": 5800000.00,
          "balanceAfter": 5000000.00
        }
      ]
    }
  ]
}
```

### 8.2 Failure Response (HTTP 422)

```json
{
  "requestId": "rev-req-7f3a9b2c-1234-5678-abcd-ef0123456789",
  "status": "REJECTED",
  "errors": [
    {
      "errorCode": "JOURNAL_ALREADY_REVERSED",
      "originalJournalId": "JNL-20260516-000012345",
      "reversalJournalId": "JNL-20260516-000012346"
    }
  ]
}
```

---

## 9. Rebook Flow (Re-posting after Reversal)

Rebook is not a standalone feature; it is a new Posting submitted after Reversal:

```
Step 1: POST /ledger/journals/{wrongJournalId}/reversal
         → Obtain reversalJournalId

Step 2: POST /ledger/postings
         → Submit the correct Posting
         → metadata includes {"rebookForReversal": "reversalJournalId"}

The two steps are independent, each idempotent; a time gap between them is allowed
```

---

## 10. Audit Requirements

Each Reversal must preserve the complete chain in the MySQL View Layer:

```
original_journal_id  ──→  reversal_journal_id
                          ├─ reversalReasonCode
                          ├─ reversalReason (free text)
                          ├─ operatorId
                          ├─ approvalRef
                          ├─ crossPeriod
                          └─ bookedAt
```

The full chain can be queried from either end, supporting F-007 Reconciliation discrepancy tracking.

---

## 11. Performance Targets

| Metric | Target |
|---|---|
| Reversal Posting P95 | ≤ 5ms (slightly higher than Posting, requires an extra read of the original Journal) |
| Idempotent Retry P95 | ≤ 1ms |

---

## 12. Acceptance Criteria

| # | Acceptance Condition | Test Method |
|---|---|---|
| AC-01 | After Reversal, original Journal status = REVERSED and balances are restored to original values | Functional Test |
| AC-02 | Attempting to Reverse an already REVERSED Journal returns `JOURNAL_ALREADY_REVERSED` | Functional Test |
| AC-03 | Attempting to Reverse a Journal with journalType=REVERSAL returns `CANNOT_REVERSE_REVERSAL` | Functional Test |
| AC-04 | Retry with the same `requestId` 1000 times; only 1 Reversal Journal is generated | Idempotency Test |
| AC-05 | Reversal does not perform balance sufficiency validation; succeeds even if account balance is insufficient | Functional Test |
| AC-06 | Cross-period Reversal is correctly flagged with `crossPeriod=true` | Functional Test |
| AC-07 | Reversal and original Journal are bidirectionally queryable in MySQL View Layer | Audit Test |
| AC-08 | Rebook flow (Reversal + new Posting) is idempotent for each step and can be retried independently | Functional Test |

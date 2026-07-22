# F-009 Accounting Period / EOD — Functional Requirements Specification

**Document Version**: v0.1
**Feature**: F-009 Accounting Period & EOD Closing
**System**: Next-Gen Internal Ledger Platform
**Status**: Draft for Review
**Dependencies**: ADR-001, F-005 Balance Snapshot, F-007 Reconciliation

---

## 1. Feature Overview

Accounting Period manages the ledger period switching logic: controlling which accounting period is open for posting, which periods are closed, and triggering snapshots and reconciliation during EOD (End of Day) closing.

---

## 2. Accounting Period State Machine

```
      Open
        │
        ▼
     [OPEN]         ← Normal postings fall into this period
        │
  EOD triggers closing
        │
        ▼
   [CLOSING]        ← New postings are prohibited; waiting for EOD tasks to complete
        │
  EOD tasks complete
        │
        ▼
    [CLOSED]        ← No posting allowed; cross-period Reversal must be marked crossPeriod=true
        │
  Next period opens
        │
        ▼
   [OPEN] (T+1)
```

---

## 3. EOD Task Sequence

When closing an accounting period, the system executes EOD tasks in the following **strict order**:

```
Step 1  Stop accepting new postings (period status → CLOSING)
Step 2  Wait for all in-flight Raft Commands to complete (drain queue)
Step 3  Trigger State Machine Snapshot (F-008)
Step 4  Learner confirms MySQL View Layer has caught up to Snapshot Index
Step 5  Execute L1 reconciliation (Journal debit-credit balance + Balance consistency)
Step 6  Execute L2 reconciliation (sub-ledger to general ledger)
Step 7  Generate EOD Balance Snapshot (current balance for all accounts)
Step 8  Generate Reconciliation Report
Step 9  Period status → CLOSED
Step 10 New period OPEN (T+1)
```

If any step fails: raise an alert, require manual intervention, do not automatically skip.

---

## 4. Accounting Period Configuration

| Field | Description |
|---|---|
| `periodId` | Unique period ID (e.g., `2026-05-16`) |
| `openTime` | Period open time |
| `scheduledCloseTime` | Scheduled closing time (e.g., daily 23:30) |
| `actualCloseTime` | Actual closing completion time |
| `status` | OPEN / CLOSING / CLOSED |
| `eodTaskStatus` | JSON status of each EOD sub-task |

---

## 5. Cross-Period Rules

| Scenario | Handling |
|---|---|
| Posting to a CLOSED period | Rejected, returns `PERIOD_CLOSED` |
| Reversal to a CLOSED period | Allowed, but marked `crossPeriod=true` |
| Manual Adjustment to a CLOSED period | Allowed, but requires additional approval (Checker must confirm cross-period in approval note) |

---

## 6. API Design

```
# Query accounting period list
GET /ledger/accounting-periods?status=OPEN

# Manually trigger EOD (for testing or re-run)
POST /ledger/accounting-periods/{periodId}/eod/trigger

# Query EOD task status
GET /ledger/accounting-periods/{periodId}/eod/status
```

---

## 7. Acceptance Criteria

| # | Acceptance Condition | Test Method |
|---|---|---|
| AC-01 | EOD tasks execute in strict order; if any step fails, do not proceed | Functional Test |
| AC-02 | New posting requests during CLOSING return `PERIOD_CLOSED` | Functional Test |
| AC-03 | Cross-period Reversal is marked `crossPeriod=true` | Functional Test |
| AC-04 | After EOD completes, EOD Balance Snapshot is consistent with State Machine | Reconciliation Test |
| AC-05 | Full EOD process (including L1/L2 reconciliation) completes within 30 minutes | Performance Test |

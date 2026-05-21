# F-003 Manual Adjustment — Functional Requirements Specification

**Document Version**: v0.1
**Feature**: F-003 Manual Adjustment
**System**: Next-Gen Internal Ledger Platform
**Status**: Draft for Review
**Dependencies**: ADR-001, F-002 Posting API, F-008 State Machine Design

---

## 1. Feature Overview

Manual Adjustment is a single-sided or double-sided ledger adjustment initiated directly by an operator, not tied to any business event (e.g., trade, fee). It is used for the following scenarios: system reconciliation discrepancy correction, manual interest posting, fee waiver, and data migration correction.

**Difference from Posting**:
- Posting is initiated by a business system with a clear business event ID
- Manual Adjustment is initiated by a human operator and must have an approval record
- Manual Adjustment uses `journalType = MANUAL_ADJUSTMENT` and is tracked separately in reports and reconciliation

---

## 2. Applicable Scenarios

| Scenario | Description |
|---|---|
| Reconciliation discrepancy correction | External settlement returns a difference that needs to be manually posted |
| Manual interest posting | Interest calculation system failure requires manual interest entry |
| Fee waiver | A posted fee needs to be manually waived (Reversal + waiver entry) |
| System migration | Initial entry of old system balances into the new Ledger |
| Error correction | Complex scenarios that cannot be resolved by Reversal |

---

## 3. Mandatory Maker-Checker Requirement

**All Manual Adjustments must go through dual Maker-Checker approval**. This is an ibank compliance requirement and cannot be bypassed:

```
Maker:
  Submits Adjustment Draft (draft status)
  → System performs pre-validation but does not execute
  → Returns draftId

Checker:
  Reviews Draft content
  → Approve → System executes Adjustment
  → Reject → Draft is voided

Constraints:
  - Maker and Checker cannot be the same person
  - Draft validity period: 24 hours (configurable)
  - Timeout without approval: automatically voided
  - Checker approval cannot be revoked (Reversal must be used)
```

---

## 4. API Design

### 4.1 Step 1: Maker Submits Draft

```
POST /ledger/adjustments/draft
```

**Request Body**

| Field | Type | Required | Description |
|---|---|---|---|
| `draftRequestId` | `string` | ✅ | Idempotency key |
| `adjustmentType` | `enum` | ✅ | Adjustment type, see table below |
| `adjustmentReason` | `string` | ✅ | Free-text description (max 1000 characters) |
| `valueDate` | `date` | ✅ | Effective date of the entry |
| `makerId` | `string` | ✅ | Maker operator ID |
| `legs` | `list<Leg>` | ✅ | Same format as F-002 Posting |
| `supportingRef` | `string` | ❌ | Supporting document reference (e.g., reconciliation report ID) |
| `metadata` | `map` | ❌ | Extension fields |

**adjustmentType Enum**

| Code | Description |
|---|---|
| `RECONCILIATION_ADJUSTMENT` | Reconciliation discrepancy correction |
| `INTEREST_ADJUSTMENT` | Manual interest adjustment |
| `FEE_WAIVER` | Fee waiver |
| `MIGRATION_ENTRY` | System migration entry |
| `ERROR_CORRECTION` | Error correction |
| `REGULATORY_ADJUSTMENT` | Regulatory requirement adjustment |

**Response (Draft Created Successfully)**

```json
{
  "draftRequestId": "draft-req-abc123",
  "draftId": "ADJ-DRAFT-20260516-000001",
  "status": "PENDING_APPROVAL",
  "expiresAt": "2026-05-17T14:30:00.000Z",
  "makerId": "ops-user-001"
}
```

### 4.2 Step 2: Checker Approval

```
POST /ledger/adjustments/{draftId}/approve
POST /ledger/adjustments/{draftId}/reject
```

**Approve Request Body**

| Field | Type | Required | Description |
|---|---|---|---|
| `requestId` | `string` | ✅ | Idempotency key (prevents duplicate approval) |
| `checkerId` | `string` | ✅ | Checker operator ID (cannot equal makerId) |
| `checkerNote` | `string` | ❌ | Approval note |

**Reject Request Body**

| Field | Type | Required | Description |
|---|---|---|---|
| `requestId` | `string` | ✅ | Idempotency key |
| `checkerId` | `string` | ✅ | Checker operator ID |
| `rejectReason` | `string` | ✅ | Rejection reason |

---

## 5. Validation Rules

### 5.1 Draft Creation Validation (Maker Submission)

| # | Rule | Error Code |
|---|---|---|
| V-01 | legs format is valid and debits equal credits | `JOURNAL_UNBALANCED` |
| V-02 | All accountIds exist and are ACTIVE | `ACCOUNT_NOT_FOUND` |
| V-03 | All balanceTypes exist in the Registry | `BALANCE_TYPE_NOT_FOUND` |
| V-04 | `adjustmentType` is valid | `INVALID_ADJUSTMENT_TYPE` |

**Note: Balance validation is NOT performed at Draft creation**. Balance validation is done when the Checker approves and the adjustment is executed.

### 5.2 Checker Approval Validation

| # | Rule | Error Code |
|---|---|---|
| V-05 | `checkerId ≠ makerId` | `MAKER_CHECKER_SAME_PERSON` |
| V-06 | Draft has not expired (before expiresAt) | `DRAFT_EXPIRED` |
| V-07 | Draft status = `PENDING_APPROVAL` | `DRAFT_NOT_PENDING` |
| V-08 | Balance validation (same as F-002 V-08 ~ V-12) | See F-002 |

---

## 6. Execution Flow

### 6.1 Maker Submits Draft (Does Not Go Through Raft)

```
Draft is only validated and stored, not posted
→ Write to MySQL adjustments_draft table
→ Return draftId
→ Do not submit RaftCommand, do not update State Machine
```

### 6.2 Checker Approves → Adjustment Is Executed (Goes Through Raft)

```
1. [Network Layer]
   Validate checkerId ≠ makerId, Draft has not expired

2. [Ledger Layer]
   Load Draft legs content from MySQL
   → Route to Account Queue in ascending order by accountId

3. [Account Queue Coordinator]
   Idempotency check (approve requestId)
   Balance validation (V-08, read in-memory State Machine)
   Build ADJUSTMENT_CMD

4. [Raft Layer]
   Submit ADJUSTMENT_CMD → Quorum commit

5. [State Machine Apply]
   Generate Journal (journalType = MANUAL_ADJUSTMENT)
   Generate JournalLine
   Update in-memory balance
   Write RocksDB WriteBatch
   Update Draft status = EXECUTED (synced to MySQL via Learner)

6. [Response]
   Return adjustmentJournalId
```

---

## 7. Draft State Machine

```
        Maker submits
             │
             ▼
    [PENDING_APPROVAL]
        │         │
    Checker    Checker
    Approve    Reject      Draft expires (24h)
        │         │              │
        ▼         ▼              ▼
   [APPROVED] [REJECTED]    [EXPIRED]
        │
        ▼
   [EXECUTED] (posting completed)
        │
        ▼
   [REVERSED] (if later reversed)
```

---

## 8. Audit Requirements

Each Manual Adjustment must have a complete audit trail stored in MySQL:

| Field | Description |
|---|---|
| `draftId` | Draft ID |
| `adjustmentJournalId` | Final posted Journal ID |
| `makerId` + `makeTime` | Who submitted the draft and when |
| `checkerId` + `checkTime` | Who approved and when |
| `checkerNote` | Approval note |
| `adjustmentType` | Adjustment type |
| `adjustmentReason` | Reason description |
| `supportingRef` | Supporting document |

---

## 9. Performance Targets

| Operation | Target |
|---|---|
| Draft creation P95 | ≤ 100ms (only writes to MySQL, does not go through Raft) |
| Checker approval P95 | ≤ 10ms (goes through Raft, slightly higher than Posting) |

---

## 10. Acceptance Criteria

| # | Acceptance Condition | Test Method |
|---|---|---|
| AC-01 | Maker submits Draft, system returns draftId, no posting occurs | Functional Test |
| AC-02 | After Checker approves, entry is correctly posted with Journal type MANUAL_ADJUSTMENT | Functional Test |
| AC-03 | If Checker and Maker are the same person, return `MAKER_CHECKER_SAME_PERSON` | Functional Test |
| AC-04 | Drafts not approved within 24 hours automatically change status to EXPIRED | Functional Test |
| AC-05 | Approving an EXPIRED / REJECTED / EXECUTED Draft returns `DRAFT_NOT_PENDING` | Functional Test |
| AC-06 | Approval records (makerId, checkerId, timestamps) are fully stored in MySQL | Audit Test |
| AC-07 | Approve operation is idempotent; retries with the same requestId do not duplicate posting | Idempotency Test |
| AC-08 | Manual Adjustment Journals are tracked separately in reports, distinct from business Postings | Report Test |

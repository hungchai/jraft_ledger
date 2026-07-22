# F-007 Reconciliation — Functional Requirements Specification

**Document Version**: v0.1  
**Feature**: F-007 Reconciliation  
**System**: Next-Gen Internal Ledger Platform  
**Status**: Draft for Review  
**Dependencies**: ADR-001, F-005 Balance Query, F-006 Journal Query, F-008 State Machine

---

## 1. Feature Overview

Reconciliation provides three levels of reconciliation capability:

| Level | Name | Description |
|---|---|---|
| L1 | **Internal Journal Reconciliation** | Validate that all Journals are balanced (debit = credit) and that transaction flows are consistent with balances |
| L2 | **Sub-ledger to General Ledger** | The sum of all client account balances should equal the corresponding company general ledger account |
| L3 | **External Settlement Reconciliation** | Compare internal ledger transaction flows with settlement files from external clearing institutions (SWIFT, HKICL, etc.) |

---

## 2. L1: Internal Journal Reconciliation

### 2.1 Reconciliation Logic

```
Executed per accounting period (end-of-day):

1. Journal debit-credit balance validation:
   SELECT journalId, SUM(CASE WHEN entryType='DEBIT' THEN amount ELSE -amount END) AS net
   FROM journal_line
   GROUP BY journalId
   HAVING ABS(net) > 0.001
   → Any non-zero result = data anomaly

2. Balance consistency validation:
   a. Take the Balance from the EOD Snapshot at period end
   b. Calculate the theoretical Balance from the previous EOD Snapshot + all current period JournalLines
   c. Difference > 0 → anomaly

3. State Machine vs MySQL View Layer consistency validation:
   a. Read all account balances from the Leader in-memory State Machine
   b. Read the latest balance_snapshot from the MySQL View Layer
   c. Difference > Learner sync delay range → anomaly
```

### 2.2 Trigger Timing

| Trigger Condition | Description |
|---|---|
| Accounting period close (daily EOD) | Main reconciliation window |
| Manual trigger | Admin API for troubleshooting |
| New Learner node joined | Validate Snapshot Transfer correctness |

---

## 3. L2: Sub-ledger to General Ledger

### 3.1 Reconciliation Logic

Using the RFQ scenario as an example:

```
COMPANY_FX_ACC (General Ledger)
  = SUM (all CLIENT_ACC AVAILABLE_BALANCE in USD)
    + COMPANY_FX_ACC's own NOSTRO_BALANCE

Defined in Reconciliation Config:
  {
    "reconRuleId": "RFQ-USD-CONTROL",
    "controlAccount": "COMPANY_FX_ACC",
    "controlBalanceType": "AVAILABLE_BALANCE",
    "currency": "USD",
    "sumAccounts": {
      "accountFilter": "accountType=CLIENT",
      "balanceType": "AVAILABLE_BALANCE",
      "currency": "USD"
    },
    "tolerance": 0.01
  }
```

Executed daily at EOD; differences exceeding tolerance generate a Reconciliation Case.

---

## 4. L3: External Settlement Reconciliation

### 4.1 Reconciliation Flow

```
Step 1: Receive external settlement file
  Supported formats: CSV, SWIFT MT940 / MT950, HKICL RTGS message
  Files uploaded via API or automatically fetched via SFTP

Step 2: Parse file and generate ExternalSettlementRecord

Step 3: Match internal JournalLine by the following keys:
  Primary Key: externalRef (external transaction ID)
  Secondary Key: amount + currency + valueDate

Step 4: Classification:
  MATCHED     → Both sides match, no discrepancy
  INTERNAL_ONLY → Internal has it, external does not
  EXTERNAL_ONLY → External has it, internal does not
  AMOUNT_MISMATCH → Both sides have it but amounts differ
  DATE_MISMATCH → Amounts match but valueDate differs

Step 5: Generate Reconciliation Report + Reconciliation Case (discrepancy items)

Step 6: Discrepancy items require manual handling or system adjustment:
  INTERNAL_ONLY → Confirm whether Reversal is needed
  EXTERNAL_ONLY → Supplemental Posting (Manual Adjustment)
  AMOUNT_MISMATCH → Reversal + Rebook
```

---

## 5. Reconciliation Case Management

Each discrepancy item generates a Reconciliation Case, tracking the full lifecycle from discovery to resolution:

### 5.1 Case State Machine

```
          Discrepancy discovered
              │
              ▼
          [OPEN]
              │
    Manual or system assignment
              │
              ▼
        [IN_PROGRESS]
         │         │
    Adjustment done    No adjustment needed
         │         │
         ▼         ▼
    [RESOLVED]  [WAIVED]
```

### 5.2 Case Structure

| Field | Description |
|---|---|
| `caseId` | Case unique ID |
| `reconType` | L1 / L2 / L3 |
| `discrepancyType` | BALANCE_MISMATCH / INTERNAL_ONLY / EXTERNAL_ONLY / AMOUNT_MISMATCH |
| `accountId` | Involved account |
| `currency` | Currency |
| `internalAmount` | Internal ledger amount |
| `externalAmount` | External settlement amount (L3 only) |
| `discrepancyAmount` | Discrepancy amount |
| `originalJournalId` | Related internal Journal |
| `externalRef` | External transaction ID |
| `status` | OPEN / IN_PROGRESS / RESOLVED / WAIVED |
| `assignedTo` | Assignee |
| `resolutionAction` | Resolution method (REVERSAL / ADJUSTMENT / WAIVED) |
| `resolutionJournalId` | Adjustment Journal ID (if any) |
| `resolvedAt` | Resolution time |

---

## 6. Reconciliation Report

A standardized report is generated after each reconciliation:

```json
{
  "reportId": "RECON-RPT-20260516",
  "reconDate": "2026-05-16",
  "generatedAt": "2026-05-16T23:45:00.000Z",
  "l1Summary": {
    "totalJournals": 125000,
    "balancedJournals": 125000,
    "unbalancedJournals": 0,
    "balanceConsistencyPassed": true
  },
  "l2Summary": {
    "rulesChecked": 5,
    "rulesPassed": 5,
    "rulesFailed": 0
  },
  "l3Summary": {
    "externalFiles": 3,
    "totalExternalRecords": 12500,
    "matched": 12498,
    "internalOnly": 1,
    "externalOnly": 1,
    "amountMismatch": 0,
    "openCases": 2
  }
}
```

---

## 7. API Design

```
# Trigger manual reconciliation
POST /ledger/reconciliation/trigger
  { "reconDate": "2026-05-16", "reconType": "L1" }

# Query reconciliation report
GET /ledger/reconciliation/reports?date=2026-05-16

# Query unresolved cases
GET /ledger/reconciliation/cases?status=OPEN&reconType=L3

# Update case status
PATCH /ledger/reconciliation/cases/{caseId}
  { "status": "RESOLVED", "resolutionAction": "ADJUSTMENT", "resolutionJournalId": "..." }

# Upload external settlement file (L3)
POST /ledger/reconciliation/external-files
  Content-Type: multipart/form-data
```

---

## 8. Performance Targets

| Operation | Target |
|---|---|
| L1 Journal debit-credit balance validation (1M daily) | ≤ 10 minutes |
| L2 Sub-ledger to general ledger (1,000 accounts) | ≤ 1 minute |
| L3 External file comparison (100k records) | ≤ 5 minutes |
| Reconciliation Report generation | ≤ 2 minutes |

---

## 9. Acceptance Criteria

| # | Acceptance Condition | Test Method |
|---|---|---|
| AC-01 | L1 validation can detect artificially created unbalanced Journals | Functional Test |
| AC-02 | L1 Balance consistency validation can detect State Machine and MySQL out-of-sync conditions | Fault Injection Test |
| AC-03 | L2 sub-ledger sum is correct; differences exceeding tolerance generate a Case | Functional Test |
| AC-04 | L3 external file comparison correctly classifies MATCHED / INTERNAL_ONLY / EXTERNAL_ONLY | Functional Test |
| AC-05 | Reconciliation Case complete flow from OPEN to RESOLVED | Functional Test |
| AC-06 | Daily EOD automatically triggers reconciliation and generates a Report | Automated Test |
| AC-07 | L1 validation for 1 million Journals completes within 10 minutes | Performance Test |
| AC-08 | Reconciliation Reports and Cases are persistently stored in MySQL and historically queryable | Functional Test |

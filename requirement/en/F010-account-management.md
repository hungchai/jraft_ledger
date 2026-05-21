# F-010 Account Management — Functional Requirements Specification

**Document Version**: v0.1
**Feature**: F-010 Account Management
**System**: Next-Gen Internal Ledger Platform
**Status**: Draft for Review
**Dependencies**: ADR-001, F-001 Balance Type Registry, F-008 State Machine

---

## 1. Feature Overview

Account Management manages the full lifecycle of an account, including creation, balance type initialization, freezing, unfreezing, closing, and maintaining account metadata for use by Posting, Balance Query, and Reconciliation.

---

## 2. Account Types

| Type | Description | Example |
|---|---|---|
| `CLIENT` | Client account | `CLIENT_ACC_001` |
| `COMPANY` | Company proprietary account (including RFQ counterparty accounts) | `COMPANY_FX_ACC` |
| `SUSPENSE` | Suspense account (for clearing discrepancy staging) | `SUSPENSE_USD_001` |
| `NOSTRO` | Our account at a counterparty bank | `NOSTRO_HSBC_USD` |
| `CONTROL` | General ledger control account (used for L2 reconciliation) | `CONTROL_CLIENT_USD` |

---

## 3. Account State Machine

```
   Create
    │
    ▼
 [ACTIVE]  ←──────────────────┐
    │                         │
    │ Freeze                Unfreeze
    ▼                         │
[FROZEN] ─────────────────────┘
    │
    │ Close (requires zero balance)
    ▼
 [CLOSED]  ← No posting allowed, cannot unfreeze, query only
```

---

## 4. API Design

### 4.1 Create Account

```
POST /ledger/accounts
```

**Request Body**

| Field | Type | Required | Description |
|---|---|---|---|
| `accountId` | `string` | ✅ | Unique account ID (specified by caller, must be unique) |
| `accountType` | `enum` | ✅ | CLIENT / COMPANY / SUSPENSE / NOSTRO / CONTROL |
| `displayName` | `string` | ✅ | Display name |
| `ownerId` | `string` | ❌ | Account owner ID (required for client accounts) |
| `balanceInitializations` | `list` | ✅ | Initial balance type + currency (default initial balance is 0) |
| `metadata` | `map` | ❌ | Extension fields |

**balanceInitializations Structure**

```json
[
  { "balanceType": "AVAILABLE_BALANCE", "currency": "USD" },
  { "balanceType": "AVAILABLE_BALANCE", "currency": "HKD" },
  { "balanceType": "TRADE_AHEAD_BALANCE", "currency": "USD" }
]
```

**Account creation goes through Raft**: account metadata must be created in the State Machine to ensure consistency across all nodes.

### 4.2 Freeze / Unfreeze / Close

```
POST /ledger/accounts/{accountId}/freeze
POST /ledger/accounts/{accountId}/unfreeze
POST /ledger/accounts/{accountId}/close
```

- Freeze / Unfreeze go through Raft (affects account state in the State Machine)
- Close must validate that all balance type balances are zero; otherwise, it is rejected

### 4.3 Query Account

```
GET /ledger/accounts/{accountId}
GET /ledger/accounts?accountType=CLIENT&ownerId=CUST-001
```

Reads from the MySQL View Layer (eventual consistency).

### 4.4 Add Balance Type to Existing Account

```
POST /ledger/accounts/{accountId}/balance-types
{ "balanceType": "BROKERAGE_BALANCE", "currency": "USD" }
```

Goes through Raft; initializes a new balance entry in the State Machine (initial value 0).

---

## 5. Validation Rules

| # | Rule | Error Code |
|---|---|---|
| V-01 | `accountId` is globally unique | `ACCOUNT_ALREADY_EXISTS` |
| V-02 | `balanceType` must exist in the F-001 Registry | `BALANCE_TYPE_NOT_FOUND` |
| V-03 | All balances must be zero when closing an account | `ACCOUNT_HAS_NON_ZERO_BALANCE` |
| V-04 | A CLOSED account cannot be unfrozen or reactivated | `ACCOUNT_CLOSED` |
| V-05 | `ownerId` is required when creating a CLIENT type account | `MISSING_OWNER_ID` |

---

## 6. Acceptance Criteria

| # | Acceptance Condition | Test Method |
|---|---|---|
| AC-01 | After account creation, account metadata is immediately queryable in the State Machine | Functional Test |
| AC-02 | After freezing an account, posting to that account returns `ACCOUNT_FROZEN` | Functional Test |
| AC-03 | After unfreezing, posting executes normally | Functional Test |
| AC-04 | Closing an account with non-zero balance returns `ACCOUNT_HAS_NON_ZERO_BALANCE` | Functional Test |
| AC-05 | After adding a Balance Type, posting to that type is immediately allowed | Functional Test |

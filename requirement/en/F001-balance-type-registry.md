# F-001 Balance Type Registry — Functional Requirements Specification

**Document Version**: v0.3 (Unified error codes to INSUFFICIENT_BALANCE / CREDIT_EXCEEDS_LIMIT; removed BALANCE_FLOOR_BREACH / BALANCE_CEILING_BREACH)  
**Feature**: F-001 Balance Type Registry  
**System**: Next-Gen Internal Ledger Platform  
**Positioning Statement**: This Ledger is a pure Booking Engine, positioned as a high-frequency, high-concurrency accounting processing core. Configuration management, limit control, maker-checker, and client-level overrides are the responsibility of upstream domains; this system does not handle them.  
**Status**: Draft for Review

---

## 1. Feature Overview

This feature defines the "Balance Type Registry" mechanism, allowing the Ledger Platform to add, modify, or deactivate Balance Types through pure configuration, **without changing any source code or redeploying**.

Each Balance Type has a set of independent behavioral attributes, such as: whether negative values are allowed, sign convention, calculation source rules, visibility scope, posting type mapping, etc. During balance calculation and validation, the system reads configuration uniformly from the Registry and dynamically applies the corresponding rules in a strategy pattern.

### System Boundary

| Responsibility | This Ledger | Upstream Domain |
|---|---|---|
| Balance calculation and booking | ✅ | ❌ |
| Balance Type configuration read and execution | ✅ | ❌ |
| Balance Type configuration add/modify | ✅ (provides API) | ✅ (caller) |
| Maker-Checker approval flow | ❌ | ✅ |
| Client/account-level limit control | ❌ | ✅ |
| Account opening, client KYC | ❌ | ✅ |

> **Design Principle**: The Ledger only knows "execute what the configuration says." The correctness of business rules is the responsibility of the configurator (upstream domain); the Ledger is responsible for executing efficiently and correctly.

### Design Goals

- **Extensibility**: Adding a new Balance Type only requires calling the Registry API to add a configuration record; no code change or release is needed.
- **High Performance**: Registry configuration is fully cached at the application layer; the balance calculation path has zero DB queries, supporting 100k concurrency level.
- **Traceability**: Each configuration has complete version history; balance calculation results carry `configVersion`, allowing traceability of the calculation rule version.
- **Hot Update**: Configuration changes take effect across all nodes within 5 seconds without restart.

---

## 2. Core Concepts

### 2.1 What is a Balance Type

A Balance Type is a balance view of an account from a specific business perspective, for example:

| Balance Type | Business Meaning | Typical Scenario |
|---|---|---|
| `AVAILABLE_BALANCE` | Current amount available to the client | Withdrawal validation, credit judgment |
| `CURRENT_BALANCE` | Actual book balance of the account | Accounting reconciliation, EOD settlement |
| `PENDING_BALANCE` | Amount not yet settled but held | In-transaction, T+N settlement |
| `HOLD_BALANCE` | Amount frozen / legally seized | Compliance freeze, collateral |
| `BROKERAGE_BALANCE` | Securities-related available amount | Brokerage business |
| `TRADE_AHEAD_BALANCE` | Trade ahead reservation (negative ledger) | Short selling, pre-authorized credit |
| `COLLATERAL_BALANCE` | Collateral calculation balance | Margin lending |
| `SHADOW_BALANCE` | Shadow ledger / management ledger view | Internal reports, regulatory filing |

The above are examples only; the system **does not hard-code any type names or logic**; all types are dynamically executed by reading configuration from the Registry.

---

## 3. Balance Type Configuration Attributes (Registry Schema)

### 3.1 Basic Identity Attributes

| Attribute Name | Type | Required | Description |
|---|---|---|---|
| `typeCode` | `string` | ✅ | Globally unique, uppercase snake_case, e.g. `TRADE_AHEAD_BALANCE`. Once created, cannot be modified. |
| `displayName` | `i18n map` | ✅ | Multi-language display name, e.g. `{"en": "Trade Ahead Balance", "zh-HK": "交易前置餘額"}` |
| `description` | `string` | ✅ | Business description, used for documentation and audit reports |
| `category` | `enum` | ✅ | `ACTUAL` / `PROJECTED` / `RESERVED` / `SHADOW` |
| `status` | `enum` | ✅ | `ACTIVE` / `INACTIVE` / `DEPRECATED` |
| `effectiveFrom` | `datetime` | ✅ | Effective date |
| `effectiveTo` | `datetime` | ❌ | Expiration date, null means long-term valid |

### 3.2 Sign and Direction Attributes (Core)

| Attribute Name | Type | Required | Description |
|---|---|---|---|
| `signConvention` | `enum` | ✅ | `NORMAL_CREDIT`: credit increases balance (standard) / `NORMAL_DEBIT`: debit increases balance (reverse) |
| `allowNegative` | `boolean` | ✅ | Whether the balance is allowed to be negative |
| `negativeSemantics` | `enum` | Conditional | Required when `allowNegative=true`; business meaning of negative value: `OVERDRAFT` / `SHORT_POSITION` / `PRE_AUTHORIZED` / `CREDIT_UTILIZATION` |
| `zeroFloorEnforce` | `boolean` | ✅ | Whether to enforce a floor of 0 (automatically false when `allowNegative=true`) |
| `overdrawnAlertThreshold` | `decimal` | ❌ | Overdraft alert trigger threshold (negative), e.g. `-500000`, only effective when `allowNegative=true` |

**Sign Semantics Constraints (applicable to all Posting paths, including Manual Adjustment):**

```
IF allowNegative=false:
  Any Posting / Adjustment must not result in Balance < 0
  On violation: reject request, return INSUFFICIENT_BALANCE

IF allowNegative=true:
  Any Posting / Adjustment must not result in Balance > 0
  (Such balances are by definition always negative or zero; positive violates business semantics)
  On violation: reject request, return CREDIT_EXCEEDS_LIMIT
```

> These two rules are **hard non-bypassable validations** of the system; no exceptions, no forced override.

**TRADE_AHEAD_BALANCE Example:**

```
signConvention     = NORMAL_DEBIT     → debit booking = balance increase (ahead reservation increases)
allowNegative      = true             → normal state, negative balance is not an anomaly
negativeSemantics  = PRE_AUTHORIZED   → negative = valid pre-authorized reservation
zeroFloorEnforce   = false            → no floor of 0
overdrawnAlertThreshold = -500000     → alert triggered only when exceeding this value
```

### 3.3 Calculation Source Rules (Composition Rules)

| Attribute Name | Type | Required | Description |
|---|---|---|---|
| `compositionLogic` | `enum` | ✅ | `SUM`: sum of matching entries / `FORMULA`: formula calculation based on other Balance Types |
| `compositionRules` | `list<CompositionRule>` | Conditional | Required when `compositionLogic=SUM` |
| `formula` | `string` | Conditional | Required when `compositionLogic=FORMULA`; only references to already defined `typeCode` are allowed, e.g. `CURRENT_BALANCE - HOLD_BALANCE - PENDING_BALANCE` |

**CompositionRule Structure:**

| Attribute Name | Type | Description |
|---|---|---|
| `includedPostingTypes` | `list<string>` | Posting types included in the calculation |
| `excludedPostingTypes` | `list<string>` | Posting types explicitly excluded |
| `includedEntryStates` | `list<enum>` | Included journal entry states: `CONFIRMED` / `PENDING` / `PROVISIONAL` |
| `sign` | `enum` | Contribution direction of this rule: `ADD` / `SUBTRACT` |

> **Formula Safety Limit**: The formula only supports arithmetic references to existing `typeCode`s; arbitrary expressions are not supported. Circular references are validated and rejected at configuration write time.

> **Implementation Status**: As of the current codebase, only `INDEPENDENT` balance types are supported. `FORMULA` composition logic (e.g. `CURRENT_BALANCE - HOLD_BALANCE - PENDING_BALANCE`) is documented here as the intended design but is **not yet implemented**. All balance types are currently treated as directly-postable buckets.

### 3.4 Currency Attributes

| Attribute Name | Type | Required | Description |
|---|---|---|---|
| `currencyScope` | `enum` | ✅ | `SINGLE_CCY` / `MULTI_CCY` / `BASE_CCY_ONLY` |
| `fxRevaluationEnabled` | `boolean` | ✅ | Whether FX Revaluation is supported |
| `fxRevaluationRateSource` | `enum` | ❌ | `MID_RATE` / `BID_RATE` / `ASK_RATE` / `CLOSING_RATE` |

### 3.5 Visibility and Access Control

| Attribute Name | Type | Required | Description |
|---|---|---|---|
| `visibilityScope` | `list<enum>` | ✅ | `INTERNAL_ONLY` / `PRODUCT_API` / `CLIENT_FACING` / `REGULATORY` |
| `queryableByClient` | `boolean` | ✅ | Whether this balance can be returned via client query API |
| `requiredPermissions` | `list<string>` | ❌ | Permission codes required to read this balance |

### 3.6 Alert and Monitoring Attributes

| Attribute Name | Type | Required | Description |
|---|---|---|---|
| `monitoringEnabled` | `boolean` | ✅ | Whether balance monitoring is enabled |
| `alertRules` | `list<AlertRule>` | ❌ | List of alert rules |

**AlertRule Structure:**

| Attribute Name | Type | Description |
|---|---|---|
| `condition` | `enum` | `BELOW_THRESHOLD` / `ABOVE_THRESHOLD` / `EQUALS_ZERO` / `NEGATIVE` |
| `threshold` | `decimal` | Threshold amount |
| `severity` | `enum` | `INFO` / `WARNING` / `CRITICAL` |
| `notificationChannel` | `list<string>` | `["EMAIL", "PAGERDUTY", "SLACK"]` |

### 3.7 Snapshot and Cache Attributes

| Attribute Name | Type | Required | Description |
|---|---|---|---|
| `snapshotEnabled` | `boolean` | ✅ | Whether scheduled snapshots are enabled |
| `snapshotFrequency` | `enum` | ❌ | `EOD` / `INTRADAY_HOURLY` / `ON_CHANGE` |
| `cacheEnabled` | `boolean` | ✅ | Whether in-memory caching of this balance is allowed |
| `cacheTtlSeconds` | `integer` | ❌ | Cache TTL in seconds; 0 means no cache |

### 3.8 Version Control Attributes

| Attribute Name | Type | Description |
|---|---|---|
| `configVersion` | `integer` | Auto-incremented on each modification, starting from 1 |
| `createdBy` | `string` | Creator operator ID (passed by upstream domain) |
| `createdAt` | `datetime` | Creation time |
| `lastModifiedBy` | `string` | Last modifier |
| `lastModifiedAt` | `datetime` | Last modification time |
| `changeReason` | `string` | Change reason must be provided for each modification |

---

## 4. Complete Configuration Examples

### 4.1 AVAILABLE_BALANCE (Independent posting bucket, negative not allowed)

> **Current implementation**: `INDEPENDENT` only. `FORMULA` composition is documented as a future design target but not yet supported.

```json
{
  "typeCode": "AVAILABLE_BALANCE",
  "displayName": {"en": "Available Balance", "zh-HK": "可用餘額"},
  "category": "PROJECTED",
  "status": "ACTIVE",
  "signConvention": "NORMAL_CREDIT",
  "allowNegative": false,
  "zeroFloorEnforce": true,
  "compositionLogic": "INDEPENDENT",
  "currencyScope": "SINGLE_CCY",
  "fxRevaluationEnabled": false,
  "visibilityScope": ["PRODUCT_API", "CLIENT_FACING"],
  "queryableByClient": true,
  "snapshotEnabled": false,
  "cacheEnabled": true,
  "cacheTtlSeconds": 5,
  "monitoringEnabled": true,
  "alertRules": [
    {"condition": "BELOW_THRESHOLD", "threshold": 0, "severity": "WARNING", "notificationChannel": ["SLACK"]}
  ]
}
```

### 4.2 TRADE_AHEAD_BALANCE (Negative ledger type, always negative or zero)

> **Current implementation**: `INDEPENDENT` only. `SUM` composition is documented as a future design target but not yet supported.

```json
{
  "typeCode": "TRADE_AHEAD_BALANCE",
  "displayName": {"en": "Trade Ahead Balance", "zh-HK": "交易前置餘額"},
  "category": "RESERVED",
  "status": "ACTIVE",
  "signConvention": "NORMAL_DEBIT",
  "allowNegative": true,
  "negativeSemantics": "PRE_AUTHORIZED",
  "zeroFloorEnforce": false,
  "overdrawnAlertThreshold": -500000.00,
  "compositionLogic": "INDEPENDENT",
  "currencyScope": "SINGLE_CCY",
  "fxRevaluationEnabled": false,
  "visibilityScope": ["INTERNAL_ONLY", "PRODUCT_API"],
  "queryableByClient": false,
  "requiredPermissions": ["TRADE_BALANCE_READ"],
  "snapshotEnabled": true,
  "snapshotFrequency": "EOD",
  "cacheEnabled": true,
  "cacheTtlSeconds": 2,
  "monitoringEnabled": true,
  "alertRules": [
    {"condition": "BELOW_THRESHOLD", "threshold": -500000.00, "severity": "CRITICAL", "notificationChannel": ["PAGERDUTY", "EMAIL"]}
  ]
}
```

### 4.3 HOLD_BALANCE (Freeze type, negative not allowed)

> **Current implementation**: `INDEPENDENT` only. `SUM` composition is documented as a future design target but not yet supported.

```json
{
  "typeCode": "HOLD_BALANCE",
  "displayName": {"en": "Hold Balance", "zh-HK": "凍結餘額"},
  "category": "RESERVED",
  "status": "ACTIVE",
  "signConvention": "NORMAL_CREDIT",
  "allowNegative": false,
  "zeroFloorEnforce": true,
  "compositionLogic": "INDEPENDENT",
  "currencyScope": "SINGLE_CCY",
  "fxRevaluationEnabled": false,
  "visibilityScope": ["INTERNAL_ONLY", "REGULATORY"],
  "queryableByClient": false,
  "requiredPermissions": ["COMPLIANCE_BALANCE_READ"],
  "snapshotEnabled": true,
  "snapshotFrequency": "EOD",
  "cacheEnabled": true,
  "cacheTtlSeconds": 30,
  "monitoringEnabled": false
}
```

---

## 5. High-Frequency Performance Design (100k Concurrency)

### 5.1 Three-Layer Cache Architecture

```
Request path (reading Registry during balance calculation):

L1: Process-level in-memory cache (local to each service instance)
    → Target hit rate: > 99.9%
    → Update method: actively refresh after subscribing to config_updated event
    → Data structure: HashMap<typeCode, BalanceTypeConfig>, lock-free read

L2: Distributed cache (Redis Cluster)
    → Fallback when L1 misses
    → Key: ledger:registry:balance_type:{typeCode}
    → TTL: 60s (passive expiration safeguard)

L3: DB (PostgreSQL / Aurora)
    → Source of truth
    → Only queried when both L1 and L2 miss (not reached in normal path)
```

### 5.2 Configuration Hot Update Flow

```
1. Upstream domain calls PUT /admin/ledger/balance-types/{typeCode}
2. Ledger Registry Service validates configuration legality (circular reference, schema validation)
3. Write to DB + history snapshot
4. Publish config_updated event to Message Bus
5. All Ledger Engine nodes subscribe and complete L1 refresh within 5 seconds
SLA: Configuration change from write to all-node effective ≤ 5 seconds
```

---

## 6. Data Model

### 6.1 balance_type_registry Table

```sql
CREATE TABLE balance_type_registry (
  type_code                    VARCHAR(64)   PRIMARY KEY,
  display_name                 JSONB         NOT NULL,
  description                  TEXT          NOT NULL,
  category                     VARCHAR(32)   NOT NULL,
  status                       VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
  sign_convention              VARCHAR(32)   NOT NULL,
  allow_negative               BOOLEAN       NOT NULL DEFAULT FALSE,
  negative_semantics           VARCHAR(32),
  zero_floor_enforce           BOOLEAN       NOT NULL DEFAULT TRUE,
  overdrawn_alert_threshold    DECIMAL(24,8),
  composition_logic            VARCHAR(16)   NOT NULL,
  formula                      TEXT,
  currency_scope               VARCHAR(32)   NOT NULL,
  fx_revaluation_enabled       BOOLEAN       NOT NULL DEFAULT FALSE,
  fx_revaluation_rate_source   VARCHAR(16),
  visibility_scope             VARCHAR[]     NOT NULL,
  queryable_by_client          BOOLEAN       NOT NULL DEFAULT FALSE,
  required_permissions         VARCHAR[],
  snapshot_enabled             BOOLEAN       NOT NULL DEFAULT FALSE,
  snapshot_frequency           VARCHAR(32),
  cache_enabled                BOOLEAN       NOT NULL DEFAULT TRUE,
  cache_ttl_seconds            INTEGER       DEFAULT 30,
  monitoring_enabled           BOOLEAN       NOT NULL DEFAULT FALSE,
  effective_from               TIMESTAMPTZ   NOT NULL,
  effective_to                 TIMESTAMPTZ,
  config_version               INTEGER       NOT NULL DEFAULT 1,
  created_by                   VARCHAR(64)   NOT NULL,
  created_at                   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
  last_modified_by             VARCHAR(64),
  last_modified_at             TIMESTAMPTZ,
  change_reason                TEXT          NOT NULL
);
```

### 6.2 balance_type_composition_rules Table

```sql
CREATE TABLE balance_type_composition_rules (
  id                       UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  type_code                VARCHAR(64) NOT NULL REFERENCES balance_type_registry(type_code),
  rule_sequence            INTEGER     NOT NULL,
  included_posting_types   VARCHAR[]   NOT NULL,
  excluded_posting_types   VARCHAR[],
  included_entry_states    VARCHAR[]   NOT NULL,
  sign                     VARCHAR(8)  NOT NULL,
  UNIQUE (type_code, rule_sequence)
);
```

### 6.3 balance_type_config_history Table

```sql
CREATE TABLE balance_type_config_history (
  id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  type_code        VARCHAR(64) NOT NULL,
  config_version   INTEGER     NOT NULL,
  snapshot_json    JSONB       NOT NULL,
  changed_by       VARCHAR(64) NOT NULL,
  changed_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  change_reason    TEXT        NOT NULL,
  UNIQUE (type_code, config_version)
);
```

---

## 7. API Design

```
POST   /admin/ledger/balance-types                        -- Create
PUT    /admin/ledger/balance-types/{typeCode}             -- Update (generates new configVersion)
PATCH  /admin/ledger/balance-types/{typeCode}/status      -- Enable / Disable
GET    /admin/ledger/balance-types                        -- Query all
GET    /admin/ledger/balance-types/{typeCode}             -- Query single
GET    /admin/ledger/balance-types/{typeCode}/history     -- Query configuration history
```

All write operations must include `changeReason`; otherwise `400 CHANGE_REASON_REQUIRED` is returned.

---

## 8. Acceptance Criteria

| # | Acceptance Condition | Test Method |
|---|---|---|
| AC-01 | Adding a Balance Type only requires calling the POST API, no code change or restart needed | Functional Test |
| AC-02 | After configuration change, all nodes' L1 cache completes refresh within 5 seconds | Hot Update Test |
| AC-03 | For balances with `allowNegative=false`, any Posting / Adjustment must not result in a value < 0 | Unit Test |
| AC-04 | For balances with `allowNegative=true`, any Posting / Adjustment must not result in a value > 0 | Unit Test |
| AC-05 | When `TRADE_AHEAD_BALANCE` falls below `overdrawnAlertThreshold`, an alert event is emitted but posting is not rejected | Integration Test |
| AC-06 | For every configuration modification, `balance_type_config_history` has a complete snapshot record | Audit Test |
| AC-07 | Balance query response includes `configVersion` | API Test |
| AC-08 | Disabled Balance Types do not participate in calculation; query returns empty | Functional Test |
| AC-09 | Modification requests without `changeReason` are rejected | Validation Test |
| AC-10 | Under 100k concurrent load test, all Registry reads go through L1 cache; DB query count is 0 | Performance Test |
| AC-11 | Circular references in Formula configuration are validated and rejected at write time | Boundary Test |

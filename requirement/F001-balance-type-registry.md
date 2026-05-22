# F-001 Balance Type Registry — 功能需求規格

**文件版本**: v0.2  
**功能**: F-001 Balance Type Registry  
**系統**: Next-Gen Internal Ledger Platform  
**定位說明**: 本 Ledger 為純 Booking Engine，定位為高頻、高並發帳務處理核心。配置管理、Limit 管控、Maker-Checker、客戶級別 Override 均屬上游 Domain 的責任，本系統不負責。  
**狀態**: Draft for Review

---

## 1. 功能概述

本功能定義「Balance Type Registry」機制，允許 Ledger Platform 以純配置方式新增、修改或停用各類 Balance Type，**無需改動任何 Source Code，無需重新部署**。

每種 Balance Type 擁有一套獨立的行為屬性，例如：是否允許負值、符號方向、計算來源規則、可見性範圍、過帳類型映射等。系統在執行 Balance 計算、校驗時，統一從 Registry 讀取配置，以策略模式動態應用對應規則。

### 系統定位邊界

| 責任 | 本 Ledger 負責 | 上游 Domain 負責 |
|---|---|---|
| Balance 計算與落帳 | ✅ | ❌ |
| Balance Type 配置讀取與執行 | ✅ | ❌ |
| Balance Type 配置的新增/修改 | ✅（提供 API） | ✅（調用方） |
| Maker-Checker 審批流程 | ❌ | ✅ |
| 客戶/帳戶級別 Limit 管控 | ❌ | ✅ |
| 帳戶開立、客戶 KYC | ❌ | ✅ |

> **設計原則**：Ledger 只認識「配置說什麼，就執行什麼」。業務規則的正確性由配置方（上游 Domain）負責，Ledger 負責高效、正確地執行。

### 設計目標

- **可擴展性**：新增 Balance Type 只需調用 Registry API 新增一條配置記錄，無需 code change / release。
- **高性能**：Registry 配置在應用層全量緩存，Balance 計算路徑零 DB 查詢，支持 10 萬並發量級。
- **可追溯性**：每條配置有完整版本歷史；Balance 計算結果附帶 `configVersion`，可追溯計算規則版本。
- **熱更新**：配置變更 5 秒內全節點生效，無需重啟。

---

## 2. 核心概念

### 2.1 Balance Type 是什麼

Balance Type 是賬戶在某一業務視角下的餘額視圖，例如：

| Balance Type | 業務含義 | 典型場景 |
|---|---|---|
| `AVAILABLE_BALANCE` | 客戶當前可動用金額 | 出金校驗、授信判斷 |
| `CURRENT_BALANCE` | 賬戶簿記實際餘額 | 會計核對、日終結算 |
| `PENDING_BALANCE` | 未最終結算但已扣留金額 | 交易中、T+N 結算 |
| `HOLD_BALANCE` | 被凍結/法律扣押的金額 | 合規凍結、抵押品 |
| `BROKERAGE_BALANCE` | 證券相關可用金額 | 券商業務 |
| `TRADE_AHEAD_BALANCE` | 交易前置佔用（負帳） | 做空、預授信額度 |
| `COLLATERAL_BALANCE` | 抵押品計算餘額 | Margin Lending |
| `SHADOW_BALANCE` | 影子帳/管理帳視圖 | 內部報表、監管申報 |

以上僅為示例，系統**不硬編碼任何 type 名稱或邏輯**；所有 type 均從 Registry 讀取配置動態執行。

---

## 3. Balance Type 配置屬性（Registry Schema）

### 3.1 基礎身份屬性

| 屬性名 | 類型 | 必填 | 說明 |
|---|---|---|---|
| `typeCode` | `string` | ✅ | 全局唯一，大寫蛇形，例如 `TRADE_AHEAD_BALANCE`。一旦創建，不可修改。 |
| `displayName` | `i18n map` | ✅ | 多語言顯示名稱，例如 `{"en": "Trade Ahead Balance", "zh-HK": "交易前置餘額"}` |
| `description` | `string` | ✅ | 業務說明，用於文檔、審計報告 |
| `category` | `enum` | ✅ | `ACTUAL` / `PROJECTED` / `RESERVED` / `SHADOW` |
| `status` | `enum` | ✅ | `ACTIVE` / `INACTIVE` / `DEPRECATED` |
| `effectiveFrom` | `datetime` | ✅ | 生效日期 |
| `effectiveTo` | `datetime` | ❌ | 失效日期，null 代表長期有效 |

### 3.2 符號與方向屬性（核心）

| 屬性名 | 類型 | 必填 | 說明 |
|---|---|---|---|
| `signConvention` | `enum` | ✅ | `NORMAL_CREDIT`：貸方增加餘額（標準） / `NORMAL_DEBIT`：借方增加餘額（反向） |
| `allowNegative` | `boolean` | ✅ | 是否允許 Balance 為負數 |
| `negativeSemantics` | `enum` | 條件必填 | 當 `allowNegative=true` 時必填，負數的業務含義：`OVERDRAFT` / `SHORT_POSITION` / `PRE_AUTHORIZED` / `CREDIT_UTILIZATION` |
| `zeroFloorEnforce` | `boolean` | ✅ | 是否強制下限為 0（`allowNegative=true` 時此項自動為 false） |
| `overdrawnAlertThreshold` | `decimal` | ❌ | 觸發超支預警閾值（負數），例如 `-500000`，僅 `allowNegative=true` 時有效 |

**符號語義約束（適用於所有 Posting 路徑，包括 Manual Adjustment）：**

```
IF allowNegative=false:
  任何 Posting / Adjustment 不得使 Balance 結果 < 0
  違反時：拒絕請求，返回 BALANCE_FLOOR_BREACH

IF allowNegative=true:
  任何 Posting / Adjustment 不得使 Balance 結果 > 0
  （此類 Balance 業務定義上永遠為負或零，正數違反業務語義）
  違反時：拒絕請求，返回 BALANCE_CEILING_BREACH
```

> 這兩條規則是系統的**硬性不可繞過校驗**，無例外，無強制覆蓋。

**TRADE_AHEAD_BALANCE 示例說明：**

```
signConvention     = NORMAL_DEBIT     → 借方記帳 = 餘額增加（前置佔用增加）
allowNegative      = true             → 正常狀態，餘額為負不代表異常
negativeSemantics  = PRE_AUTHORIZED   → 負數 = 有效預授信佔用
zeroFloorEnforce   = false            → 不設下限 0
overdrawnAlertThreshold = -500000     → 超過此值才觸發告警
```

### 3.3 計算來源規則（Composition Rules）

| 屬性名 | 類型 | 必填 | 說明 |
|---|---|---|---|
| `compositionLogic` | `enum` | ✅ | `SUM`：對符合條件的流水求和 / `FORMULA`：基於其他 Balance Type 做公式計算 |
| `compositionRules` | `list<CompositionRule>` | 條件必填 | 當 `compositionLogic=SUM` 時必填 |
| `formula` | `string` | 條件必填 | 當 `compositionLogic=FORMULA` 時必填，只允許引用已定義的 `typeCode`，例如 `CURRENT_BALANCE - HOLD_BALANCE - PENDING_BALANCE` |

**CompositionRule 結構：**

| 屬性名 | 類型 | 說明 |
|---|---|---|
| `includedPostingTypes` | `list<string>` | 納入計算的 posting type |
| `excludedPostingTypes` | `list<string>` | 明確排除的 posting type |
| `includedEntryStates` | `list<enum>` | 納入的 journal entry 狀態：`CONFIRMED` / `PENDING` / `PROVISIONAL` |
| `sign` | `enum` | 此規則貢獻方向：`ADD` / `SUBTRACT` |

> **Formula 安全限制**：formula 只支持對已存在的 `typeCode` 做四則運算引用，不支持任意表達式。循環引用在配置寫入時校驗並拒絕。

> **實現狀態**：目前程式碼僅支援 `INDEPENDENT`（獨立直接記帳）的 balance type。`FORMULA` 組合邏輯（例如 `CURRENT_BALANCE - HOLD_BALANCE - PENDING_BALANCE`）在此文件記載為設計目標，但**尚未實作**。所有 balance type 目前皆視為可直接記帳的獨立桶子。

### 3.4 貨幣屬性

| 屬性名 | 類型 | 必填 | 說明 |
|---|---|---|---|
| `currencyScope` | `enum` | ✅ | `SINGLE_CCY` / `MULTI_CCY` / `BASE_CCY_ONLY` |
| `fxRevaluationEnabled` | `boolean` | ✅ | 是否支持 FX Revaluation |
| `fxRevaluationRateSource` | `enum` | ❌ | `MID_RATE` / `BID_RATE` / `ASK_RATE` / `CLOSING_RATE` |

### 3.5 可見性與存取控制

| 屬性名 | 類型 | 必填 | 說明 |
|---|---|---|---|
| `visibilityScope` | `list<enum>` | ✅ | `INTERNAL_ONLY` / `PRODUCT_API` / `CLIENT_FACING` / `REGULATORY` |
| `queryableByClient` | `boolean` | ✅ | 是否可透過客戶查詢 API 返回 |
| `requiredPermissions` | `list<string>` | ❌ | 讀取此 Balance 所需的 permission code |

### 3.6 告警與監控屬性

| 屬性名 | 類型 | 必填 | 說明 |
|---|---|---|---|
| `monitoringEnabled` | `boolean` | ✅ | 是否啟用餘額監控 |
| `alertRules` | `list<AlertRule>` | ❌ | 告警規則列表 |

**AlertRule 結構：**

| 屬性名 | 類型 | 說明 |
|---|---|---|
| `condition` | `enum` | `BELOW_THRESHOLD` / `ABOVE_THRESHOLD` / `EQUALS_ZERO` / `NEGATIVE` |
| `threshold` | `decimal` | 閾值金額 |
| `severity` | `enum` | `INFO` / `WARNING` / `CRITICAL` |
| `notificationChannel` | `list<string>` | `["EMAIL", "PAGERDUTY", "SLACK"]` |

### 3.7 快照與快取屬性

| 屬性名 | 類型 | 必填 | 說明 |
|---|---|---|---|
| `snapshotEnabled` | `boolean` | ✅ | 是否啟用定時快照 |
| `snapshotFrequency` | `enum` | ❌ | `EOD` / `INTRADAY_HOURLY` / `ON_CHANGE` |
| `cacheEnabled` | `boolean` | ✅ | 是否允許 in-memory 緩存此 Balance |
| `cacheTtlSeconds` | `integer` | ❌ | 緩存存活時間（秒），0 代表不緩存 |

### 3.8 版本控制屬性

| 屬性名 | 類型 | 說明 |
|---|---|---|
| `configVersion` | `integer` | 每次修改自動遞增，從 1 開始 |
| `createdBy` | `string` | 創建者 operator ID（由上游 Domain 傳入） |
| `createdAt` | `datetime` | 創建時間 |
| `lastModifiedBy` | `string` | 最後修改者 |
| `lastModifiedAt` | `datetime` | 最後修改時間 |
| `changeReason` | `string` | 每次修改必須填寫變更原因 |

---

## 4. 完整配置示例

### 4.1 AVAILABLE_BALANCE（獨立記帳桶，不允許負數）

> **當前實現**：僅支援 `INDEPENDENT`。`FORMULA` 組合邏輯屬未來設計目標，尚未實作。

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

### 4.2 TRADE_AHEAD_BALANCE（負帳型，永遠為負或零）

> **當前實現**：僅支援 `INDEPENDENT`。`SUM` 組合邏輯屬未來設計目標，尚未實作。

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

### 4.3 HOLD_BALANCE（凍結型，不允許負數）

> **當前實現**：僅支援 `INDEPENDENT`。`SUM` 組合邏輯屬未來設計目標，尚未實作。

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

## 5. 高頻性能設計（10 萬並發）

### 5.1 三層緩存架構

```
請求路徑（Balance 計算時讀取 Registry）:

L1: Process-level in-memory cache（每個服務實例本地）
    → 命中率目標：> 99.9%
    → 更新方式：訂閱 config_updated 事件後主動刷新
    → 數據結構：HashMap<typeCode, BalanceTypeConfig>，讀無鎖

L2: Distributed cache（Redis Cluster）
    → 作為 L1 miss 時的回源
    → Key: ledger:registry:balance_type:{typeCode}
    → TTL: 60s（被動過期兜底）

L3: DB（PostgreSQL / Aurora）
    → 作為 source of truth
    → 僅在 L1/L2 均 miss 時查詢（正常路徑不觸達）
```

### 5.2 配置熱更新流程

```
1. 上游 Domain 調用 PUT /admin/ledger/balance-types/{typeCode}
2. Ledger Registry Service 校驗配置合法性（循環引用、schema 校驗）
3. 寫入 DB + 歷史快照
4. 發布 config_updated 事件至 Message Bus
5. 所有 Ledger Engine 節點訂閱，5 秒內完成 L1 刷新
SLA: 配置變更從寫入到全節點生效 ≤ 5 秒
```

---

## 6. 數據模型

### 6.1 balance_type_registry 表

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

### 6.2 balance_type_composition_rules 表

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

### 6.3 balance_type_config_history 表

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

## 7. API 設計

```
POST   /admin/ledger/balance-types                        -- 新增
PUT    /admin/ledger/balance-types/{typeCode}             -- 修改（生成新 configVersion）
PATCH  /admin/ledger/balance-types/{typeCode}/status      -- 啟用 / 停用
GET    /admin/ledger/balance-types                        -- 查詢所有
GET    /admin/ledger/balance-types/{typeCode}             -- 查詢單個
GET    /admin/ledger/balance-types/{typeCode}/history     -- 查詢配置歷史
```

所有寫操作必須包含 `changeReason`，否則返回 `400 CHANGE_REASON_REQUIRED`。

---

## 8. 驗收標準

| # | 驗收條件 | 測試方式 |
|---|---|---|
| AC-01 | 新增 Balance Type 只需調用 POST API，無需 code change 或重啟 | 功能測試 |
| AC-02 | 配置變更後，5 秒內所有節點 L1 cache 完成刷新 | 熱更新測試 |
| AC-03 | `allowNegative=false` 的 Balance，任何 Posting / Adjustment 不得使結果 < 0 | 單元測試 |
| AC-04 | `allowNegative=true` 的 Balance，任何 Posting / Adjustment 不得使結果 > 0 | 單元測試 |
| AC-05 | `TRADE_AHEAD_BALANCE` 餘額低於 `overdrawnAlertThreshold` 時，發出告警事件但不拒絕過帳 | 集成測試 |
| AC-06 | 每次修改配置，`balance_type_config_history` 有完整快照記錄 | 審計測試 |
| AC-07 | Balance 查詢 Response 包含 `configVersion` | API 測試 |
| AC-08 | 停用的 Balance Type 不參與計算，查詢返回空 | 功能測試 |
| AC-09 | 未填 `changeReason` 的修改請求被拒絕 | 校驗測試 |
| AC-10 | 10 萬並發壓測下，Registry 讀取全走 L1 cache，DB 查詢數量為 0 | 性能測試 |
| AC-11 | Formula 配置中出現循環引用，寫入時即時校驗拒絕 | 邊界測試 |

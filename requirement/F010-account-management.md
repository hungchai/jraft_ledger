# F-010 Account Management — 功能需求規格

**文件版本**: v0.1  
**功能**: F-010 Account Management（帳戶管理）  
**系統**: Next-Gen Internal Ledger Platform  
**狀態**: Draft for Review  
**依賴**: ADR-001、F-001 Balance Type Registry、F-008 State Machine

---

## 1. 功能概述

Account Management 管理帳戶的完整生命週期，包括創建、Balance Type 初始化、凍結、解凍、關閉，並維護帳戶 metadata 供 Posting、Balance Query、Reconciliation 使用。

---

## 2. 帳戶類型

| 類型 | 說明 | 示例 |
|---|---|---|
| `CLIENT` | 客戶帳戶 | `CLIENT_ACC_001` |
| `COMPANY` | 公司自有帳戶（含 RFQ 對手方帳戶） | `COMPANY_FX_ACC` |
| `SUSPENSE` | 過渡帳戶（清算差異暫存） | `SUSPENSE_USD_001` |
| `NOSTRO` | 我方在對手行的帳戶 | `NOSTRO_HSBC_USD` |
| `CONTROL` | 總帳控制帳戶（L2 對帳用） | `CONTROL_CLIENT_USD` |

---

## 3. 帳戶狀態機

```
   創建
    │
    ▼
 [ACTIVE]  ←──────────────────┐
    │                         │
    │ 凍結                  解凍
    ▼                         │
[FROZEN] ─────────────────────┘
    │
    │ 關閉（需餘額為零）
    ▼
 [CLOSED]  ← 不可過帳，不可解凍，只可查詢
```

---

## 4. API 設計

### 4.1 創建帳戶

```
POST /ledger/accounts
```

**Request Body**

| 欄位 | 類型 | 必填 | 說明 |
|---|---|---|---|
| `accountId` | `string` | ✅ | 帳戶唯一 ID（由調用方指定，不可重複） |
| `accountType` | `enum` | ✅ | CLIENT / COMPANY / SUSPENSE / NOSTRO / CONTROL |
| `displayName` | `string` | ✅ | 顯示名稱 |
| `ownerId` | `string` | ❌ | 帳戶所有者 ID（客戶帳戶必填） |
| `balanceInitializations` | `list` | ✅ | 初始化的 balance type + currency（初始餘額默認 0） |
| `metadata` | `map` | ❌ | 擴展欄位 |

**balanceInitializations 結構**

```json
[
  { "balanceType": "AVAILABLE_BALANCE", "currency": "USD" },
  { "balanceType": "AVAILABLE_BALANCE", "currency": "HKD" },
  { "balanceType": "TRADE_AHEAD_BALANCE", "currency": "USD" }
]
```

**帳戶創建走 Raft**：帳戶 metadata 需在 State Machine 中創建，確保所有節點一致。

### 4.2 凍結 / 解凍 / 關閉

```
POST /ledger/accounts/{accountId}/freeze
POST /ledger/accounts/{accountId}/unfreeze
POST /ledger/accounts/{accountId}/close
```

- 凍結 / 解凍走 Raft（影響 State Machine 帳戶狀態）
- 關閉需校驗所有 Balance Type 餘額為零，否則拒絕

### 4.3 查詢帳戶

```
GET /ledger/accounts/{accountId}
GET /ledger/accounts?accountType=CLIENT&ownerId=CUST-001
```

讀 MySQL View Layer（最終一致性）。

### 4.4 新增 Balance Type 到已有帳戶

```
POST /ledger/accounts/{accountId}/balance-types
{ "balanceType": "BROKERAGE_BALANCE", "currency": "USD" }
```

走 Raft，在 State Machine 中初始化新 balance entry（初始值 0）。

---

## 5. 校驗規則

| # | 規則 | 錯誤碼 |
|---|---|---|
| V-01 | `accountId` 全局唯一 | `ACCOUNT_ALREADY_EXISTS` |
| V-02 | `balanceType` 必須在 F-001 Registry 中存在 | `BALANCE_TYPE_NOT_FOUND` |
| V-03 | 關閉帳戶時所有 Balance 必須為零 | `ACCOUNT_HAS_NON_ZERO_BALANCE` |
| V-04 | 已 CLOSED 帳戶不可解凍或重新激活 | `ACCOUNT_CLOSED` |
| V-05 | CLIENT 類型帳戶創建時 `ownerId` 必填 | `MISSING_OWNER_ID` |

---

## 6. 驗收標準

| # | 驗收條件 | 測試方式 |
|---|---|---|
| AC-01 | 創建帳戶後，State Machine 立即可查到帳戶 metadata | 功能測試 |
| AC-02 | 凍結帳戶後，Posting 到該帳戶返回 `ACCOUNT_FROZEN` | 功能測試 |
| AC-03 | 解凍後，Posting 正常執行 | 功能測試 |
| AC-04 | 帳戶餘額不為零時關閉，返回 `ACCOUNT_HAS_NON_ZERO_BALANCE` | 功能測試 |
| AC-05 | 新增 Balance Type 後，可立即對該 type 做 Posting | 功能測試 |

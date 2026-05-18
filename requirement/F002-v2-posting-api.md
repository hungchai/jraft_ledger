# F-002 v2 Posting API — Batch Atomic Posting（Raft 架構更新版）

**文件版本**: v0.2（基於 ADR-001 更新）  
**功能**: F-002 Posting API  
**系統**: Next-Gen Internal Ledger Platform  
**狀態**: Draft for Review  
**變更摘要**: 寫路徑由 PostgreSQL 直寫改為 Raft State Machine，Balance 校驗由 DB 讀改為 in-memory 讀

---

## 1. 架構前提（依 ADR-001）

- **寫路徑**：所有 Posting 請求由 Raft Leader 的 Account-Level Queue 串行處理，State Machine 更新 in-memory balance，持久化到 RocksDB
- **無 DB 直寫**：Posting 寫路徑完全不碰 MySQL；MySQL 由 Learner 異步同步，用於查詢
- **Balance 校驗**：讀取 in-memory State Machine，不讀 DB，P95 ≤ 2ms

---

## 2. 功能概述

Posting API 接受包含一或多條 leg 的帳務請求，在 Raft Leader 節點上原子執行：雙分錄生成、餘額校驗、State Machine 更新、RocksDB 持久化，並透過 Learner 異步同步至 MySQL View Layer。

---

## 3. Request 結構

### 3.1 頂層請求體

| 欄位 | 類型 | 必填 | 說明 |
|---|---|---|---|
| `requestId` | `string` | ✅ | 冪等鍵，全局唯一（UUID v7 推薦） |
| `businessEventType` | `string` | ✅ | 業務事件類型，如 `RFQ_SETTLEMENT`, `WITHDRAWAL`, `FEE` |
| `businessEventRef` | `string` | ✅ | 上游業務事件 ID |
| `valueDate` | `date` | ✅ | 帳務生效日 |
| `legs` | `list<Leg>` | ✅ | 至少一條 leg，每條 leg 包含兩條 JournalLine（debit + credit） |
| `metadata` | `map<string,string>` | ❌ | 擴展欄位，如 traceId、操作員 ID |

### 3.2 Leg 結構

| 欄位 | 類型 | 必填 | 說明 |
|---|---|---|---|
| `legId` | `string` | ✅ | 本 leg 唯一 ID（由 caller 生成） |
| `postingType` | `string` | ✅ | 過帳類型，如 `TRADE_SETTLEMENT`, `FEE`, `INTEREST` |
| `lines` | `list<JournalLine>` | ✅ | 必須包含至少一對 DEBIT + CREDIT |

### 3.3 JournalLine 結構

| 欄位 | 類型 | 必填 | 說明 |
|---|---|---|---|
| `accountId` | `string` | ✅ | 目標帳戶 |
| `balanceType` | `string` | ✅ | 必須在 Balance Type Registry（F-001）中存在 |
| `currency` | `string` | ✅ | ISO 4217 幣種代碼 |
| `entryType` | `enum` | ✅ | `DEBIT` / `CREDIT` |
| `amount` | `decimal` | ✅ | 必須 > 0 |
| `description` | `string` | ❌ | 分錄描述 |

### 3.4 RFQ 場景請求示例

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

---

## 4. 校驗規則

### 4.1 前置校驗（Network Layer，進 Raft 前）

| # | 規則 | 錯誤碼 |
|---|---|---|
| V-01 | `requestId` 格式合法 | `INVALID_REQUEST_ID` |
| V-02 | `legs` 至少一條 | `LEGS_EMPTY` |
| V-03 | 每條 leg 的所有 `balanceType` 必須在 Registry 中存在且為 ACTIVE | `BALANCE_TYPE_NOT_FOUND` |
| V-04 | `amount` 必須 > 0 | `INVALID_AMOUNT` |
| V-05 | 每條 leg 的 DEBIT 總額 = CREDIT 總額（按幣種） | `JOURNAL_UNBALANCED` |

### 4.2 冪等校驗（Account Worker，in-memory）

| # | 規則 | 結果 |
|---|---|---|
| V-06 | `requestId` 已存在且 `COMPLETED`，直接返回原結果 | 冪等成功 |
| V-07 | `requestId` 已存在且 `PROCESSING`，返回 `409 PROCESSING` | 等待重試 |

### 4.3 業務校驗（State Machine，in-memory balance）

| # | 規則 | 錯誤碼 |
|---|---|---|
| V-08 | 每個涉及帳戶必須存在 | `ACCOUNT_NOT_FOUND` |
| V-09 | 每個帳戶的對應 balanceType + currency 必須已初始化 | `BALANCE_NOT_INITIALIZED` |
| V-10 | `allowNegative=false` 的 balance，DEBIT 後不得低於 0 | `INSUFFICIENT_BALANCE` |
| V-11 | `allowNegative=true` 的 balance（如 TRADE_AHEAD_BALANCE），CREDIT 後不得高於 0 | `CREDIT_EXCEEDS_LIMIT` |
| V-12 | 帳戶未被凍結（`account.status = ACTIVE`） | `ACCOUNT_FROZEN` |

---

## 5. 執行流程（Raft 寫路徑）

```
1. [Network Layer]
   接收 HTTP 請求 → 反序列化 → 前置校驗（V-01 ~ V-05）
   → 放入 request_queue

2. [Ledger Layer]
   從 request_queue 取出
   → 按所有涉及 accountId 升序排列（防死鎖）
   → 路由到各 Account Queue

3. [Account Queue Coordinator]
   等待所有涉及帳戶的 queue 就緒（Multi-Account 協調）
   → 冪等校驗（V-06 ~ V-07）
   → 業務校驗（V-08 ~ V-12，讀 in-memory State Machine）
   → 構建 RaftCommand（包含所有 JournalLine）

4. [Raft Layer]
   提交 RaftCommand 至 Raft Group
   → Leader 寫 Raft Log
   → 複製到 Follower（達 Quorum 後 commit）

5. [State Machine Apply]
   Apply Raft Log：
   a. 生成 Journal（journalId、journalType=NORMAL）
   b. 生成所有 JournalLine（含 balanceBefore、balanceAfter）
   c. 原子更新所有涉及帳戶的 in-memory balance
   d. 寫 RocksDB（journal + balance，WAL 保證持久性）
   e. 更新 in-memory idempotency map（requestId → result）

6. [Learner 異步同步]
   Learner 監聽 Raft Log
   → 異步寫入 MySQL journal_line、account_balance（View Layer）

7. [Response]
   透過 response_queue 返回結果給 client
```

---

## 6. 多帳戶原子性（RFQ 場景）

RFQ 涉及 CLIENT_ACC + COMPANY_ACC（hotspot）兩個帳戶：

```
傳統問題：
  CLIENT_ACC 在 Account Queue A
  COMPANY_ACC 在 Account Queue B
  如何保證跨 queue 原子？

解法：Multi-Account RaftCommand

1. 按 accountId 升序排列涉及帳戶（CLIENT_ACC_001 < COMPANY_FX_ACC）
2. 依序在兩個 Account Queue 中取得「協調票」（Coordination Token）
3. 兩個 queue 都就緒後，構建一個包含所有 legs 的單一 RaftCommand
4. RaftCommand 在 State Machine 中 apply 時：
   - 一次性更新所有帳戶 in-memory balance
   - 視為原子操作
5. 任何一個帳戶校驗失敗 → 整個 RaftCommand 拒絕，所有帳戶餘額不變
```

COMPANY_ACC 是 hotspot，但因為所有請求都在同一個 Account Queue 裡串行排隊，**沒有鎖競爭，只有 nanosecond 級的 queue 等待**。

---

## 7. Response 結構

### 7.1 成功 Response（HTTP 200）

```json
{
  "requestId": "req-550e8400-e29b-41d4-a716-446655440000",
  "status": "COMPLETED",
  "journalId": "JNL-20260516-000012345",
  "bookedAt": "2026-05-16T10:30:22.341Z",
  "legs": [
    {
      "legId": "leg-001",
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
        },
        {
          "journalLineId": "JL-000024690",
          "accountId": "COMPANY_FX_ACC",
          "balanceType": "AVAILABLE_BALANCE",
          "currency": "USD",
          "entryType": "CREDIT",
          "amount": 800000.00,
          "balanceBefore": 5000000.00,
          "balanceAfter": 5800000.00
        }
      ]
    }
  ]
}
```

### 7.2 失敗 Response（HTTP 422）

```json
{
  "requestId": "req-550e8400-e29b-41d4-a716-446655440001",
  "status": "REJECTED",
  "errors": [
    {
      "errorCode": "INSUFFICIENT_BALANCE",
      "accountId": "CLIENT_ACC_001",
      "balanceType": "AVAILABLE_BALANCE",
      "currency": "USD",
      "required": 800000.00,
      "available": 500000.00
    }
  ]
}
```

---

## 8. 性能目標（依 ADR-001）

| 指標 | 目標 | 達成原理 |
|---|---|---|
| Posting P95 | ≤ 3ms | 不碰 MySQL，純 in-memory + RocksDB WAL |
| Balance 校驗 P95 | ≤ 0.5ms | 直讀 in-memory State Machine |
| Hotspot 帳戶（COMPANY_ACC） | 與普通帳戶相同 | Account Queue 串行，無 DB row lock 競爭 |
| 冪等重試 P95 | ≤ 1ms | in-memory idempotency map 命中 |

---

## 9. 驗收標準

| # | 驗收條件 | 測試方式 |
|---|---|---|
| AC-01 | RFQ 雙帳戶 Posting，CLIENT_ACC 和 COMPANY_ACC 餘額同時原子更新 | 功能測試 |
| AC-02 | `allowNegative=false` 帳戶餘額不足時，整筆請求被拒絕，所有帳戶餘額不變 | 功能測試 |
| AC-03 | 相同 `requestId` 重試 1000 次，只生成 1 筆有效 Journal | 冪等測試 |
| AC-04 | 1000 並發 RFQ 打同一 COMPANY_ACC，Posting P95 ≤ 3ms | 性能測試 |
| AC-05 | COMPANY_ACC hotspot 下無重複出金、無餘額不一致 | 並發安全測試 |
| AC-06 | Raft Leader 宕機後，in-flight 請求可憑 idempotencyKey 重試成功 | 故障恢復測試 |
| AC-07 | Posting 完成後，Learner 在 1 秒內同步至 MySQL View Layer | 一致性測試 |
| AC-08 | 每筆 Posting 在 MySQL Journal 可查到完整 balanceBefore / balanceAfter | 審計測試 |

# F-004 Reversal — 功能需求規格

**文件版本**: v0.2（失敗 Response 升級為 v0.9 `errorCodes[]` + `errorDetails` 格式）  
**功能**: F-004 Reversal（反轉已有 Journal）  
**系統**: Next-Gen Internal Ledger Platform  
**狀態**: Draft for Review  
**依賴**: ADR-001、F-002 Posting API、F-008 State Machine Design

---

## 1. 功能概述

Reversal 是對一筆已過帳 Journal 的完整對沖。它生成一筆鏡像 Journal，所有 JournalLine 的 DEBIT / CREDIT 互換，金額相同，使兩筆 Journal 的淨效果歸零。

**核心原則**：
- 已過帳分錄**不允許修改或刪除**，只能 Reversal
- Reversal 本身也是一筆 Journal，同樣 append-only，不可再被修改
- Reversal 後若需重新入帳，必須提交新的 Posting 請求（Rebook）

---

## 2. 適用場景

| 場景 | 說明 |
|---|---|
| 交易取消 | RFQ 成交後客戶或系統取消，需反轉已入帳 |
| 錯誤入帳 | Posting 使用錯誤金額、幣種、帳戶，需先 Reverse 再 Rebook |
| 系統對帳差異 | 外部清算結果與內部帳務不符，需反轉後重新對帳入帳 |
| 日終調整 | 帳期關閉前發現錯誤，需當日反轉 |

---

## 3. 約束條件

| # | 約束 | 說明 |
|---|---|---|
| C-01 | 只能 Reverse `status = CONFIRMED` 的 Journal | 已 Reversed 的 Journal 不能再 Reverse |
| C-02 | 不能部分 Reverse | 必須反轉整筆 Journal 的所有 JournalLine，不允許只反轉某條 leg |
| C-03 | Reversal 不檢查餘額充足性 | Reverse 是對沖操作，必須成功；若餘額不足（如資金已被動用），系統仍執行 Reversal，允許餘額出現對應的負數（由後續流程處理） |
| C-04 | Reversal 本身不可被 Reverse | 防止無限反轉鏈 |
| C-05 | 跨帳期 Reversal 需標記 | 若原 Journal 的 valueDate 在已關閉帳期，需標記 `crossPeriod=true`，供報表系統處理 |

---

## 4. Request 結構

### 4.1 API

```
POST /ledger/journals/{originalJournalId}/reversal
```

### 4.2 Request Body

| 欄位 | 類型 | 必填 | 說明 |
|---|---|---|---|
| `requestId` | `string` | ✅ | 冪等鍵，全局唯一（UUID v7） |
| `reversalReason` | `string` | ✅ | 反轉原因，自由文字（最長 500 字） |
| `reversalReasonCode` | `enum` | ✅ | 原因碼，見下表 |
| `valueDate` | `date` | ✅ | Reversal 的帳務生效日（可與原 Journal 不同） |
| `operatorId` | `string` | ✅ | 操作員 ID，用於審計 |
| `approvalRef` | `string` | ❌ | 審批單號（若系統需要 maker-checker） |
| `metadata` | `map<string,string>` | ❌ | 擴展欄位 |

### 4.3 reversalReasonCode 枚舉

| Code | 說明 |
|---|---|
| `TRADE_CANCELLED` | 交易取消 |
| `WRONG_AMOUNT` | 金額錯誤 |
| `WRONG_ACCOUNT` | 帳戶錯誤 |
| `WRONG_CURRENCY` | 幣種錯誤 |
| `SYSTEM_ERROR` | 系統錯誤 |
| `RECONCILIATION_ADJUSTMENT` | 對帳調整 |
| `COMPLIANCE_REQUIREMENT` | 合規要求 |
| `OTHER` | 其他（需附 reversalReason 說明） |

### 4.4 請求示例

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

## 5. 校驗規則

### 5.1 前置校驗（Network Layer）

| # | 規則 | 錯誤碼 |
|---|---|---|
| V-01 | `requestId` 格式合法 | `INVALID_REQUEST_ID` |
| V-02 | `originalJournalId` 格式合法 | `INVALID_JOURNAL_ID` |
| V-03 | `reversalReasonCode` 在枚舉範圍內 | `INVALID_REASON_CODE` |
| V-04 | `operatorId` 不為空 | `MISSING_OPERATOR` |

### 5.2 業務校驗（State Machine，in-memory）

| # | 規則 | 錯誤碼 |
|---|---|---|
| V-05 | `originalJournalId` 對應的 Journal 存在 | `JOURNAL_NOT_FOUND` |
| V-06 | 原 Journal `status = CONFIRMED` | `JOURNAL_ALREADY_REVERSED` |
| V-07 | 原 Journal `journalType ≠ REVERSAL` | `CANNOT_REVERSE_REVERSAL` |
| V-08 | 冪等：`requestId` 已存在則直接返回原結果 | — |

### 5.3 跨帳期標記

| # | 規則 | 處理 |
|---|---|---|
| V-09 | 若原 Journal 的 `valueDate` 所在帳期已關閉 | 標記 `crossPeriod=true`，仍允許 Reversal 執行，但通知報表系統 |

---

## 6. 執行流程（Raft 寫路徑）

```
1. [Network Layer]
   接收 POST 請求 → 前置校驗（V-01 ~ V-04）
   → 放入 request_queue

2. [Ledger Layer]
   從 request_queue 取出
   → 從 State Machine 讀取原 Journal 所有涉及的 accountId
   → 按 accountId 升序排列
   → 路由到各 Account Queue（Multi-Account Coordinator）

3. [Account Queue Coordinator]
   等待所有涉及帳戶的 queue 就緒
   → 冪等檢查（V-08）
   → 業務校驗（V-05 ~ V-09）
   → 構建 REVERSAL_CMD

4. [Raft Layer]
   提交 REVERSAL_CMD → Leader 複製到 Follower → Quorum commit

5. [State Machine Apply]
   a. 生成 Reversal Journal：
      journalType = REVERSAL
      originalJournalId = {originalJournalId}
      status = CONFIRMED
      crossPeriod = true/false

   b. 生成鏡像 JournalLine（逐條反轉）：
      原 DEBIT → CREDIT
      原 CREDIT → DEBIT
      金額不變
      balanceBefore / balanceAfter 基於當前 State Machine 計算

   c. 更新原 Journal status：
      original Journal.status = REVERSED
      original Journal.reversalJournalId = {新 Reversal journalId}

   d. 原子更新所有涉及帳戶的 in-memory balance

   e. 以 WriteBatch 原子寫入 RocksDB：
      - 新 Reversal Journal
      - 所有新 JournalLine
      - 更新原 Journal（status + reversalJournalId）
      - 更新 balance

6. [Learner 異步同步]
   Learner 把 Reversal Journal 和更新的原 Journal 同步到 MySQL View Layer

7. [Response]
   返回 Reversal 結果
```

---

## 7. State Machine 的 Journal 狀態機

```
         Posting
            │
            ▼
       [CONFIRMED]  ←─── 正常過帳後的狀態
            │
            │ Reversal
            ▼
       [REVERSED]   ←─── 不可再被 Reverse

       [REVERSAL]   ←─── Reversal Journal 本身的 journalType，status 仍為 CONFIRMED
                         不可被 Reverse（V-07 校驗）
```

---

## 8. Response 結構

### 8.1 成功 Response（HTTP 200）

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

### 8.2 失敗 Response（HTTP 422）

```json
{
  "requestId": "rev-req-7f3a9b2c-1234-5678-abcd-ef0123456789",
  "status": "REJECTED",
  "errorCodes": ["JOURNAL_ALREADY_REVERSED"],
  "errorDetails": {
    "originalJournalId": "JNL-20260516-000012345",
    "reversalJournalId": "JNL-20260516-000012346"
  }
}
```

---

## 9. Rebook 流程（Reversal 後重新入帳）

Rebook 不是獨立功能，而是 Reversal 後接著提交新 Posting：

```
Step 1: POST /ledger/journals/{wrongJournalId}/reversal
         → 取得 reversalJournalId

Step 2: POST /ledger/postings
         → 提交正確的 Posting
         → metadata 帶 {"rebookForReversal": "reversalJournalId"}

兩步獨立，各自冪等，中間允許有時間差
```

---

## 10. 審計要求

每筆 Reversal 必須在 MySQL View Layer 保存以下完整鏈路：

```
original_journal_id  ──→  reversal_journal_id
                          ├─ reversalReasonCode
                          ├─ reversalReason（自由文字）
                          ├─ operatorId
                          ├─ approvalRef
                          ├─ crossPeriod
                          └─ bookedAt
```

可從任意一端查到完整鏈路，支持 F-007 Reconciliation 差異追蹤。

---

## 11. 性能目標

| 指標 | 目標 |
|---|---|
| Reversal Posting P95 | ≤ 5ms（略高於 Posting，需多讀一次原 Journal） |
| 冪等重試 P95 | ≤ 1ms |

---

## 12. 驗收標準

| # | 驗收條件 | 測試方式 |
|---|---|---|
| AC-01 | Reversal 後，原 Journal status = REVERSED，餘額回復原值 | 功能測試 |
| AC-02 | 對已 REVERSED 的 Journal 再次 Reverse，返回 `JOURNAL_ALREADY_REVERSED` | 功能測試 |
| AC-03 | 對 journalType=REVERSAL 的 Journal 執行 Reverse，返回 `CANNOT_REVERSE_REVERSAL` | 功能測試 |
| AC-04 | 相同 `requestId` 重試 1000 次，只生成 1 筆 Reversal Journal | 冪等測試 |
| AC-05 | Reversal 不做餘額充足性校驗，即使帳戶餘額不足仍執行成功 | 功能測試 |
| AC-06 | 跨帳期 Reversal，`crossPeriod=true` 正確標記 | 功能測試 |
| AC-07 | Reversal 和原 Journal 在 MySQL View Layer 可雙向查詢鏈路 | 審計測試 |
| AC-08 | Rebook 流程（Reversal + 新 Posting）各自冪等，可獨立重試 | 功能測試 |

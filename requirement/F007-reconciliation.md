# F-007 Reconciliation — 功能需求規格

**文件版本**: v0.1  
**功能**: F-007 Reconciliation（對帳）  
**系統**: Next-Gen Internal Ledger Platform  
**狀態**: Draft for Review  
**依賴**: ADR-001、F-005 Balance Query、F-006 Journal Query、F-008 State Machine

---

## 1. 功能概述

Reconciliation 提供三層對帳能力：

| 層級 | 名稱 | 說明 |
|---|---|---|
| L1 | **內部 Journal 對帳** | 驗證所有 Journal 借貸平衡，Journal 流水與 Balance 一致 |
| L2 | **子帳對總帳** | 所有客戶帳戶的 Balance 加總應等於公司對應總帳帳戶 |
| L3 | **外部清算對帳** | 內部帳務流水與外部清算機構（SWIFT、HKICL 等）的結算文件比對 |

---

## 2. L1：內部 Journal 對帳

### 2.1 對帳邏輯

```
每個帳期（日終）執行：

1. Journal 借貸平衡校驗：
   SELECT journalId, SUM(CASE WHEN entryType='DEBIT' THEN amount ELSE -amount END) AS net
   FROM journal_line
   GROUP BY journalId
   HAVING ABS(net) > 0.001
   → 任何不為零的結果 = 資料異常

2. Balance 一致性校驗：
   a. 從 EOD Snapshot 取帳期末的 Balance
   b. 從上一個 EOD Snapshot + 當期所有 JournalLine 計算理論 Balance
   c. 兩者差異 > 0 → 異常

3. State Machine vs MySQL View Layer 一致性校驗：
   a. 讀取 Leader in-memory State Machine 的所有帳戶 Balance
   b. 讀取 MySQL View Layer 的最新 balance_snapshot
   c. 差異 > Learner 同步延遲範圍 → 異常
```

### 2.2 觸發時機

| 觸發條件 | 說明 |
|---|---|
| 帳期關閉（每日 EOD） | 主要對帳窗口 |
| 手動觸發 | 管理 API，用於排查問題 |
| 新 Learner 節點加入後 | 驗證 Snapshot Transfer 正確性 |

---

## 3. L2：子帳對總帳

### 3.1 對帳邏輯

以 RFQ 場景為例：

```
COMPANY_FX_ACC（總帳）
  = SUM（所有 CLIENT_ACC 的 AVAILABLE_BALANCE in USD）
    + COMPANY_FX_ACC 自身的 NOSTRO_BALANCE

定義在 Reconciliation Config 中：
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

每日 EOD 執行，差異超過 tolerance 產生 Reconciliation Case。

---

## 4. L3：外部清算對帳

### 4.1 對帳流程

```
Step 1：接收外部清算文件
  支持格式：CSV、SWIFT MT940 / MT950、HKICL RTGS 報文
  文件通過 API 上傳 或 SFTP 自動拉取

Step 2：解析文件，生成 ExternalSettlementRecord

Step 3：按以下 Key 匹配內部 JournalLine：
  主 Key：externalRef（外部交易 ID）
  次 Key：amount + currency + valueDate

Step 4：分類：
  MATCHED     → 雙方一致，無差異
  INTERNAL_ONLY → 內部有，外部沒有
  EXTERNAL_ONLY → 外部有，內部沒有
  AMOUNT_MISMATCH → 雙方都有但金額不符
  DATE_MISMATCH → 金額一致但 valueDate 不符

Step 5：生成 Reconciliation Report + Reconciliation Case（差異項）

Step 6：差異項需人工處理或系統補帳：
  INTERNAL_ONLY → 確認是否需 Reversal
  EXTERNAL_ONLY → 補 Posting（Manual Adjustment）
  AMOUNT_MISMATCH → Reversal + Rebook
```

---

## 5. Reconciliation Case 管理

每個差異項生成一個 Reconciliation Case，追蹤從發現到解決的完整生命週期：

### 5.1 Case 狀態機

```
          發現差異
              │
              ▼
          [OPEN]
              │
    人工或系統 分配
              │
              ▼
        [IN_PROGRESS]
         │         │
    補帳完成    無需補帳
         │         │
         ▼         ▼
    [RESOLVED]  [WAIVED]
```

### 5.2 Case 結構

| 欄位 | 說明 |
|---|---|
| `caseId` | Case 唯一 ID |
| `reconType` | L1 / L2 / L3 |
| `discrepancyType` | BALANCE_MISMATCH / INTERNAL_ONLY / EXTERNAL_ONLY / AMOUNT_MISMATCH |
| `accountId` | 涉及帳戶 |
| `currency` | 幣種 |
| `internalAmount` | 內部帳務金額 |
| `externalAmount` | 外部清算金額（L3 才有） |
| `discrepancyAmount` | 差異金額 |
| `originalJournalId` | 相關內部 Journal |
| `externalRef` | 外部交易 ID |
| `status` | OPEN / IN_PROGRESS / RESOLVED / WAIVED |
| `assignedTo` | 處理人 |
| `resolutionAction` | 解決方式（REVERSAL / ADJUSTMENT / WAIVED）|
| `resolutionJournalId` | 補帳 Journal ID（如有） |
| `resolvedAt` | 解決時間 |

---

## 6. Reconciliation Report

每次對帳後生成標準化報告：

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

## 7. API 設計

```
# 觸發手動對帳
POST /ledger/reconciliation/trigger
  { "reconDate": "2026-05-16", "reconType": "L1" }

# 查詢對帳報告
GET /ledger/reconciliation/reports?date=2026-05-16

# 查詢未解決 Case
GET /ledger/reconciliation/cases?status=OPEN&reconType=L3

# 更新 Case 狀態
PATCH /ledger/reconciliation/cases/{caseId}
  { "status": "RESOLVED", "resolutionAction": "ADJUSTMENT", "resolutionJournalId": "..." }

# 上傳外部清算文件（L3）
POST /ledger/reconciliation/external-files
  Content-Type: multipart/form-data
```

---

## 8. 性能目標

| 操作 | 目標 |
|---|---|
| L1 Journal 借貸平衡校驗（每日 100 萬筆） | ≤ 10 分鐘 |
| L2 子帳對總帳（1000 個帳戶） | ≤ 1 分鐘 |
| L3 外部文件比對（10 萬條） | ≤ 5 分鐘 |
| Reconciliation Report 生成 | ≤ 2 分鐘 |

---

## 9. 驗收標準

| # | 驗收條件 | 測試方式 |
|---|---|---|
| AC-01 | L1 校驗能發現人工製造的借貸不平衡 Journal | 功能測試 |
| AC-02 | L1 Balance 一致性校驗能發現 State Machine 與 MySQL 不同步的情況 | 故障注入測試 |
| AC-03 | L2 子帳加總正確，差異超 tolerance 產生 Case | 功能測試 |
| AC-04 | L3 外部文件比對，MATCHED / INTERNAL_ONLY / EXTERNAL_ONLY 分類正確 | 功能測試 |
| AC-05 | Reconciliation Case 從 OPEN 到 RESOLVED 完整流程 | 功能測試 |
| AC-06 | 每日 EOD 自動觸發對帳，生成 Report | 自動化測試 |
| AC-07 | L1 百萬筆 Journal 校驗在 10 分鐘內完成 | 性能測試 |
| AC-08 | 對帳 Report 和 Case 在 MySQL 長期保存，可查歷史 | 功能測試 |

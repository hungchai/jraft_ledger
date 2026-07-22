# F-006 Journal Query — 功能需求規格

**文件版本**: v0.1  
**功能**: F-006 Journal Query（帳務流水查詢）  
**系統**: Next-Gen Internal Ledger Platform  
**狀態**: Draft for Review  
**依賴**: ADR-001（CQRS 架構）、F-002、F-003、F-004、F-008 State Machine

---

## 1. 功能概述

Journal Query 提供對所有帳務流水的查詢能力，讀取 MySQL View Layer（Learner 異步同步），支持多維度過濾、分頁、排序。

**重要**：Journal Query 讀 MySQL View Layer，是最終一致性（通常落後 Leader < 1 秒）。對需要強一致性的場景（如即時餘額），使用 F-005 Balance Query。

---

## 2. 查詢維度

系統支持以下查詢入口，可組合使用：

| 維度 | 欄位 | 說明 |
|---|---|---|
| Journal | `journalId` | 精確查詢單筆 Journal |
| 帳戶 | `accountId` | 查某帳戶的所有流水 |
| 業務事件 | `businessEventRef` | 查某業務事件（如 RFQ-ID）的所有帳務 |
| 請求 | `requestId` | 查某次 Posting 請求的結果 |
| 時間範圍 | `bookedFrom` + `bookedTo` | 按入帳時間範圍 |
| Value Date | `valueDateFrom` + `valueDateTo` | 按帳務生效日範圍 |
| Journal 類型 | `journalType` | `NORMAL`, `REVERSAL`, `MANUAL_ADJUSTMENT` |
| 狀態 | `status` | `CONFIRMED`, `REVERSED` |
| 幣種 | `currency` | ISO 4217 |
| Balance Type | `balanceType` | 按 Balance Type 過濾 |
| 操作員 | `operatorId` | Manual Adjustment 的操作員 |

---

## 3. API 設計

### 3.1 查詢單筆 Journal（含所有 JournalLine）

```
GET /ledger/journals/{journalId}
```

**Response**

```json
{
  "journalId": "JNL-20260516-000012345",
  "journalType": "NORMAL",
  "status": "REVERSED",
  "requestId": "req-550e8400-e29b-41d4-a716-446655440000",
  "businessEventType": "RFQ_SETTLEMENT",
  "businessEventRef": "RFQ-2026051600123",
  "valueDate": "2026-05-16",
  "bookedAt": "2026-05-16T10:30:22.341Z",
  "reversalJournalId": "JNL-20260516-000012346",
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
    }
  ],
  "dataSource": "VIEW_LAYER",
  "viewLayerDelay": "< 1s"
}
```

### 3.2 按帳戶查流水（分頁）

```
GET /ledger/accounts/{accountId}/journals
    ?currency=USD
    &balanceType=AVAILABLE_BALANCE
    &journalType=NORMAL
    &valueDateFrom=2026-05-01
    &valueDateTo=2026-05-16
    &page=0
    &size=50
    &sort=bookedAt,desc
```

**Response**

```json
{
  "accountId": "CLIENT_ACC_001",
  "totalCount": 1250,
  "page": 0,
  "size": 50,
  "items": [ ... ],
  "dataSource": "VIEW_LAYER"
}
```

### 3.3 按業務事件查帳務（全鏈路追溯）

```
GET /ledger/journals?businessEventRef=RFQ-2026051600123
```

返回該業務事件的所有相關 Journal，包括：
- 原始 Posting Journal
- Reversal Journal（如有）
- Rebook Journal（如有）
- 完整鏈路（`originalJournalId` → `reversalJournalId`）

### 3.4 按 requestId 查詢（冪等確認）

```
GET /ledger/journals?requestId=req-550e8400-e29b-41d4-a716-446655440000
```

用於上游系統確認某次 Posting 是否成功入帳。

---

## 4. 全鏈路追溯

對任意一筆 Journal，可查到完整的前後關聯鏈路：

```
GET /ledger/journals/{journalId}/chain
```

**Response**

```json
{
  "chain": [
    {
      "journalId": "JNL-20260516-000012345",
      "journalType": "NORMAL",
      "status": "REVERSED",
      "businessEventRef": "RFQ-2026051600123",
      "bookedAt": "2026-05-16T10:30:22.341Z",
      "relationship": "ORIGINAL"
    },
    {
      "journalId": "JNL-20260516-000012346",
      "journalType": "REVERSAL",
      "status": "CONFIRMED",
      "bookedAt": "2026-05-16T14:22:11.500Z",
      "relationship": "REVERSAL_OF"
    },
    {
      "journalId": "JNL-20260516-000012399",
      "journalType": "NORMAL",
      "status": "CONFIRMED",
      "businessEventRef": "RFQ-2026051600123-REBOOK",
      "bookedAt": "2026-05-16T14:25:00.000Z",
      "relationship": "REBOOK_AFTER_REVERSAL"
    }
  ]
}
```

---

## 5. MySQL View Layer 索引設計

為支持上述查詢性能，需在 MySQL journal 和 journal_line 表建立以下索引：

```sql
-- journal 表
CREATE INDEX idx_journal_account_booked  ON journal_line (account_id, booked_at DESC);
CREATE INDEX idx_journal_biz_event       ON journal      (business_event_ref);
CREATE INDEX idx_journal_request_id      ON journal      (request_id);
CREATE INDEX idx_journal_value_date      ON journal      (value_date, account_id);
CREATE INDEX idx_journal_type_status     ON journal      (journal_type, status);

-- journal_line 表
CREATE INDEX idx_line_journal_id         ON journal_line (journal_id);
CREATE INDEX idx_line_account_currency   ON journal_line (account_id, currency, balance_type);
```

---

## 6. 性能目標

| 查詢類型 | 目標 | 說明 |
|---|---|---|
| 單筆 Journal 點查 | P95 ≤ 10ms | 索引命中 |
| 帳戶流水（50 條） | P95 ≤ 30ms | 分頁查詢 |
| 業務事件全鏈路 | P95 ≤ 50ms | 跨表關聯 |
| 全鏈路追溯（chain） | P95 ≤ 100ms | 遞歸關聯查詢 |

---

## 7. 驗收標準

| # | 驗收條件 | 測試方式 |
|---|---|---|
| AC-01 | 按 journalId 精確查詢，返回完整 JournalLine | 功能測試 |
| AC-02 | 按 accountId + 時間範圍查詢，分頁正確 | 功能測試 |
| AC-03 | 按 businessEventRef 可查到原始 + Reversal + Rebook 全部 Journal | 功能測試 |
| AC-04 | chain API 正確返回前後關聯的完整鏈路 | 功能測試 |
| AC-05 | Posting 完成後，Learner 在 1 秒內同步，Journal 可查到 | 一致性測試 |
| AC-06 | 帳戶流水 50 條查詢，P95 ≤ 30ms | 性能測試 |
| AC-07 | `dataSource` 欄位正確標明 `VIEW_LAYER` | 功能測試 |

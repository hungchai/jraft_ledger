# F-011b Posting Completion Event — Kafka Output

**文件版本**: v0.1  
**功能**: F-011b Posting Completion Event（過帳完成 Kafka 事件）  
**系統**: Next-Gen Internal Ledger Platform  
**狀態**: Draft for Review  
**依賴**: F-002 Posting API、F-011 Balance Change Event、F-008 State Machine

---

## 1. 功能概述

每次 Raft State Machine 完成一個 `requestId` 的處理（無論 `COMPLETED` 或 `REJECTED`），立即向 Kafka 發出一個 `PostingCompletionEvent`。

此事件面向**調用方業務系統**（如 RFQ Engine、Order Management、Withdrawal Service），讓其無需輪詢 API，即可異步感知過帳結果。

### 與 F-011 BalanceChangeEvent 的關係

| | F-011 BalanceChangeEvent | F-011b PostingCompletionEvent |
|---|---|---|
| **粒度** | 每條 JournalLine 一個事件 | 每個 requestId 一個事件 |
| **觸發** | 任何 balance 變動 | Posting / Reversal / Manual Adj 完成 |
| **主要消費者** | Risk Engine、VAMP、流水通知 | 調用方業務系統 |
| **包含 result** | ❌（只有 delta） | ✅（完整 legs + balanceBefore/After） |
| **包含 REJECTED** | ❌（無 balance 變動） | ✅（含錯誤原因） |

兩個 event **互補**，下游按需訂閱，不互相取代。

---

## 2. 觸發場景

| 場景 | `result` | 說明 |
|---|---|---|
| Posting 成功 Raft commit | `COMPLETED` | 所有 legs 原子完成 |
| Posting 因業務校驗失敗（餘額不足等） | `REJECTED` | 所有帳戶餘額不變 |
| Reversal 成功 | `COMPLETED` | commandType=REVERSAL |
| Manual Adjustment Checker approve 後完成 | `COMPLETED` | commandType=MANUAL_ADJUSTMENT |
| Raft commit 失敗（Leader 宕機等） | ❌ 不發 | 由調用方超時重試，重試後正常發出 |

---

## 3. Kafka Topic 設計

```
Topic Name:  ledger.posting.completion.v1
Partitions:  64
Retention:   7 天
Compression: LZ4
```

### Partition Key 策略

```
partitionKey = requestId
```

**設計理由**：
- 同一 `requestId` 的冪等重試事件落在同一 partition，下游順序消費方便去重
- `requestId` 分布均勻（UUID v7），不存在 hotspot
- 調用方按 `requestId` 訂閱時可精確路由

---

## 4. 事件結構（PostingCompletionEvent）

### 4.1 COMPLETED 示例

```json
{
  "eventId": "evt-01JWXYZ999888777ABCDEF",
  "eventType": "POSTING_COMPLETION",
  "eventVersion": "1.0",
  "occurredAt": "2026-05-17T23:30:00.234567890Z",

  "idempotencyKey": "req-550e8400-e29b-41d4-a716-446655440000",

  "requestId": "req-550e8400-e29b-41d4-a716-446655440000",
  "commandType": "POSTING",
  "businessEventType": "RFQ_SETTLEMENT",
  "businessEventRef": "RFQ-2026051600123",

  "result": "COMPLETED",
  "journalId": "JNL-20260516-000012345",
  "bookedAt": "2026-05-17T23:30:00.123456789Z",
  "raftLogIndex": 100245,

  "legs": [
    {
      "legId": "leg-001",
      "postingType": "TRADE_SETTLEMENT",
      "lines": [
        {
          "journalLineId": "JLL-01JWXYZ-001",
          "accountId": "CLIENT_ACC_001",
          "balanceType": "AVAILABLE_BALANCE",
          "currency": "USD",
          "entryType": "DEBIT",
          "amount": "800000.00",
          "balanceBefore": "1000000.00",
          "balanceAfter": "200000.00"
        },
        {
          "journalLineId": "JLL-01JWXYZ-002",
          "accountId": "COMPANY_FX_ACC",
          "balanceType": "AVAILABLE_BALANCE",
          "currency": "USD",
          "entryType": "CREDIT",
          "amount": "800000.00",
          "balanceBefore": "5000000.00",
          "balanceAfter": "5800000.00"
        }
      ]
    },
    {
      "legId": "leg-002",
      "postingType": "TRADE_SETTLEMENT",
      "lines": [
        {
          "journalLineId": "JLL-01JWXYZ-003",
          "accountId": "COMPANY_FX_ACC",
          "balanceType": "AVAILABLE_BALANCE",
          "currency": "HKD",
          "entryType": "DEBIT",
          "amount": "6240000.00",
          "balanceBefore": "10000000.00",
          "balanceAfter": "3760000.00"
        },
        {
          "journalLineId": "JLL-01JWXYZ-004",
          "accountId": "CLIENT_ACC_001",
          "balanceType": "AVAILABLE_BALANCE",
          "currency": "HKD",
          "entryType": "CREDIT",
          "amount": "6240000.00",
          "balanceBefore": "500000.00",
          "balanceAfter": "6740000.00"
        }
      ]
    }
  ],

  "errors": null,

  "traceId": "trace-abc123",
  "metadata": {
    "operatorId": "system",
    "sourceSystem": "LEDGER"
  }
}
```

### 4.2 REJECTED 示例

```json
{
  "eventId": "evt-01JWXYZ999888777FEDCBA",
  "eventType": "POSTING_COMPLETION",
  "eventVersion": "1.0",
  "occurredAt": "2026-05-17T23:30:01.000000000Z",

  "idempotencyKey": "req-550e8400-e29b-41d4-a716-446655440001",

  "requestId": "req-550e8400-e29b-41d4-a716-446655440001",
  "commandType": "POSTING",
  "businessEventType": "RFQ_SETTLEMENT",
  "businessEventRef": "RFQ-2026051600124",

  "result": "REJECTED",
  "journalId": null,
  "bookedAt": null,
  "raftLogIndex": null,

  "legs": [],

  "errors": [
    {
      "errorCode": "INSUFFICIENT_BALANCE",
      "accountId": "CLIENT_ACC_001",
      "balanceType": "AVAILABLE_BALANCE",
      "currency": "USD",
      "required": "800000.00",
      "available": "100000.00"
    }
  ],

  "traceId": "trace-def456",
  "metadata": {
    "operatorId": "system",
    "sourceSystem": "LEDGER"
  }
}
```

---

## 5. 欄位說明

### 5.1 事件標識

| 欄位 | 類型 | 說明 |
|---|---|---|
| `eventId` | `string` | 事件唯一 ID（UUID v7） |
| `eventType` | `string` | 固定值 `POSTING_COMPLETION` |
| `eventVersion` | `string` | Schema 版本 |
| `occurredAt` | `timestamp` | State Machine apply 完成時間（UTC，納秒） |
| `idempotencyKey` | `string` | 同 `requestId`，下游按此去重 |

### 5.2 請求標識

| 欄位 | 類型 | 說明 |
|---|---|---|
| `requestId` | `string` | 原始請求冪等 Key |
| `commandType` | `enum` | `POSTING` / `REVERSAL` / `MANUAL_ADJUSTMENT` |
| `businessEventType` | `string` | 業務事件類型（如 `RFQ_SETTLEMENT`） |
| `businessEventRef` | `string` | 業務事件引用（如 RFQ ID） |

### 5.3 結果

| 欄位 | 類型 | COMPLETED | REJECTED |
|---|---|---|---|
| `result` | `enum` | `COMPLETED` | `REJECTED` |
| `journalId` | `string` | 已生成的 Journal ID | `null` |
| `bookedAt` | `timestamp` | Raft commit 時間 | `null` |
| `raftLogIndex` | `long` | Raft log index | `null` |
| `legs` | `list` | 完整 legs + 所有 lines 含 balanceBefore/After | `[]`（空） |
| `errors` | `list` | `null` | 錯誤詳情列表 |

### 5.4 JournalLine（legs 內）

| 欄位 | 類型 | 說明 |
|---|---|---|
| `journalLineId` | `string` | JournalLine 唯一 ID |
| `accountId` | `string` | 帳戶 ID |
| `balanceType` | `string` | Balance Type |
| `currency` | `string` | 幣種 |
| `entryType` | `enum` | `DEBIT` / `CREDIT` |
| `amount` | `decimal string` | 金額（正數） |
| `balanceBefore` | `decimal string` | 變動前餘額（State Machine 確認值） |
| `balanceAfter` | `decimal string` | 變動後餘額（State Machine 確認值） |

### 5.5 Error（errors 內，REJECTED 時）

| 欄位 | 類型 | 說明 |
|---|---|---|
| `errorCode` | `string` | 錯誤碼（同 F-002 校驗規則） |
| `accountId` | `string` | 涉及帳戶（如有） |
| `balanceType` | `string` | 涉及 balance type（如有） |
| `currency` | `string` | 幣種（如有） |
| `required` | `decimal string` | 所需金額（INSUFFICIENT_BALANCE 時） |
| `available` | `decimal string` | 實際可用金額（INSUFFICIENT_BALANCE 時） |

---

## 6. 發送架構

與 F-011 共用 Outbox 模式，但寫入不同的 CF_OUTBOX key prefix：

```
Raft Leader
    │
    │  onStateMachineApply()
    ▼
LedgerStateMachine.apply(command)
    │
    ├── 更新 in-memory balance（COMPLETED）
    │   或 記錄 REJECTED 原因
    ├── 寫 RocksDB WriteBatch：
    │     ├── CF_BALANCE（僅 COMPLETED）
    │     ├── CF_JOURNAL（僅 COMPLETED）
    │     ├── CF_OUTBOX key: outbox:balance:...  （F-011，僅 COMPLETED）
    │     └── CF_OUTBOX key: outbox:completion:requestId  ← 本功能
    │
    └── AsyncKafkaPublisher 異步發送兩個 topic
            ├── ledger.balance.change.v1     （F-011）
            └── ledger.posting.completion.v1 （F-011b）
```

**REJECTED 也走 Outbox**：雖然 REJECTED 無 balance 變動，`PostingCompletionEvent` 同樣寫入 CF_OUTBOX，確保 at-least-once 投遞給調用方。

---

## 7. 驗收標準

| # | 驗收條件 | 測試方式 |
|---|---|---|
| AC-01 | Posting COMPLETED 後，`ledger.posting.completion.v1` 收到事件，result=COMPLETED | 功能測試 |
| AC-02 | Posting REJECTED 後，收到事件，result=REJECTED，errors 包含正確 errorCode | 功能測試 |
| AC-03 | COMPLETED 事件的 legs 包含所有 JournalLine，balanceBefore/After 與 State Machine 一致 | 功能測試 |
| AC-04 | 多 leg RFQ Posting，所有 legs 均出現在同一個 completion event 中 | 功能測試 |
| AC-05 | 相同 requestId 冪等重試，只發一個 completion event | 功能測試 |
| AC-06 | Kafka publish 失敗，Posting 結果不受影響，Outbox 補發 | 故障注入測試 |
| AC-07 | 節點重啟後，Outbox 中未發的 completion event 繼續投遞 | 故障注入測試 |
| AC-08 | REJECTED 事件的 legs 為空陣列，journalId=null | 功能測試 |
| AC-09 | completion event 的 occurredAt 與 F-011 balanceChangeEvent 的 occurredAt 一致（同一 Raft commit） | 功能測試 |
| AC-10 | P95 延遲（Raft commit 到 Kafka confirm）≤ 100ms | 性能測試 |

# F-011 Balance Change Event — Kafka Output

**文件版本**: v0.2
**功能**: F-011 Balance Change Event（餘額變動 Kafka 事件）
**系統**: Next-Gen Internal Ledger Platform
**狀態**: Draft for Review
**依賴**: ADR-001、F-008 State Machine、F-002 Posting、F-003 Manual Adjustment、F-004 Reversal

> **v0.2 變更摘要**：Event schema 新增 `accountSeq` / `prevAccountSeq` 欄位（eventVersion 升至 1.1），補充 5.4 帳務時序欄位說明，新增 AC-12 / AC-13 / AC-14。

---

## 1. 功能概述

每次 Raft State Machine 成功 commit 任何導致 Balance 變動的 Command（Posting、Reversal、Manual Adjustment），立即向 Kafka 發出 `BalanceChangeEvent`，供下游系統（Risk Engine、VAMP、流水通知等）消費。

核心原則：
- **Raft commit 後才發**：只有 Quorum 確認的變動才發事件，不發未確認的變動
- **At-least-once 投遞**：允許重複，下游按 `idempotencyKey` 做冪等處理
- **不阻塞主流程**：Kafka publish 失敗不影響 Posting 結果，異步重試
- **所有 Balance Type 均發**：AVAILABLE_BALANCE、TRADE_AHEAD_BALANCE、BROKERAGE_BALANCE 等一律發

---

## 2. 觸發場景

| 觸發來源 | Command Type | 說明 |
|---|---|---|
| Posting API（F-002） | `POSTING` | 正常入帳，每條 JournalLine 對應的 balance 變動 |
| Reversal（F-004） | `REVERSAL` | 反轉入帳 |
| Manual Adjustment（F-003） | `MANUAL_ADJUSTMENT` | 人工調帳 Checker 審批後入帳 |

**不觸發場景**：
- Balance Query（只讀）
- Account 凍結 / 解凍 / 關閉（無 Balance 數值變動）
- Reconciliation Case 更新（無直接 Balance 變動）
- EOD Snapshot 生成（非新入帳）

---

## 3. Kafka Topic 設計

```
Topic Name:  ledger.balance.change.v1
Partitions:  64（可按需擴展）
Retention:   7 天
Compression: LZ4
```

### Partition Key 策略

```
partitionKey = accountId + ":" + balanceType + ":" + currency
```

**設計理由**：
- 同一帳戶、同一 balance type、同一幣種的事件保證**順序性**
- 不同帳戶並行，避免 hotspot partition（COMPANY_FX_ACC 不會打爆單個 partition）
- 下游 Risk Engine 按帳戶訂閱時可精確消費對應 partition

---

## 4. 事件結構（BalanceChangeEvent）

```json
{
  "eventId": "evt-01JWXYZ123456789ABCDEF",
  "eventType": "BALANCE_CHANGE",
  "eventVersion": "1.1",
  "occurredAt": "2026-05-17T23:30:00.123456789Z",

  "idempotencyKey": "req-01JWXYZ000000001:CLIENT_ACC_001:AVAILABLE_BALANCE:USD",

  "commandType": "POSTING",
  "journalId": "JNL-01JWXYZ000000001",
  "journalLineId": "JLL-01JWXYZ000000001-01",
  "requestId": "req-01JWXYZ000000001",
  "businessEventRef": "RFQ-20260517-001",
  "traceId": "trace-abc123",

  "accountId": "CLIENT_ACC_001",
  "balanceType": "AVAILABLE_BALANCE",
  "currency": "USD",

  "entryType": "DEBIT",
  "amount": "800.00",

  "preBalance": "1000.00",
  "postBalance": "200.00",
  "balanceDelta": "-800.00",

  "raftLogIndex": 100245,
  "stateVersion": 100245,
  "accountSeq": 1042,
  "prevAccountSeq": 1041,
  "accountingDate": "2026-05-17",
  "valueDate": "2026-05-17",

  "metadata": {
    "operatorId": "system",
    "sourceSystem": "LEDGER",
    "crossPeriod": false
  }
}
```

---

## 5. 欄位說明

### 5.1 事件標識

| 欄位 | 類型 | 說明 |
|---|---|---|
| `eventId` | `string` | 事件唯一 ID（UUID v7，含時間排序） |
| `eventType` | `string` | 固定值 `BALANCE_CHANGE` |
| `eventVersion` | `string` | Schema 版本，用於下游兼容性管理 |
| `occurredAt` | `timestamp` | Raft commit 完成時間（納秒精度，UTC） |
| `idempotencyKey` | `string` | `requestId:accountId:balanceType:currency`，下游冪等去重用 |

### 5.2 來源追溯

| 欄位 | 類型 | 說明 |
|---|---|---|
| `commandType` | `enum` | `POSTING` / `REVERSAL` / `MANUAL_ADJUSTMENT` |
| `journalId` | `string` | 對應的 Journal ID |
| `journalLineId` | `string` | 對應的具體 JournalLine ID |
| `requestId` | `string` | 原始請求冪等 Key |
| `businessEventRef` | `string` | 業務事件引用（如 RFQ ID、Order ID） |
| `traceId` | `string` | 分布式追蹤 ID |

### 5.3 帳戶與 Balance

| 欄位 | 類型 | 說明 |
|---|---|---|
| `accountId` | `string` | 帳戶 ID |
| `balanceType` | `string` | Balance Type（來自 F-001 Registry） |
| `currency` | `string` | 幣種（ISO 4217） |
| `entryType` | `enum` | `DEBIT` / `CREDIT` |
| `amount` | `decimal string` | 本次變動金額（正數，方向由 entryType 決定） |
| `preBalance` | `decimal string` | 變動前餘額 |
| `postBalance` | `decimal string` | 變動後餘額 |
| `balanceDelta` | `decimal string` | 淨變動（負數 = DEBIT，正數 = CREDIT） |

> **金額用 decimal string 而非 float**，避免浮點精度問題，下游按 BigDecimal 解析

### 5.4 帳務時序【v0.2 更新】

| 欄位 | 類型 | 說明 |
|---|---|---|
| `raftLogIndex` | `long` | Raft log index，全局順序 |
| `stateVersion` | `long` | State Machine version（與 raftLogIndex 一致） |
| `accountSeq` | `long` | **【v0.2 新增】** Per-account 單調遞增序號，維度為 `accountId + balanceType + currency`，從 1 開始；Posting / Reversal / Adjustment 每次 balance 變動均遞增 |
| `prevAccountSeq` | `long` | **【v0.2 新增】** 上一條同維度事件的 `accountSeq`；正常情況 `accountSeq = prevAccountSeq + 1`；首條事件 `prevAccountSeq = 0`；下游可用此欄位偵測 event stream gap |
| `accountingDate` | `date` | 記帳日期（帳期） |
| `valueDate` | `date` | 起息日期 |

---

## 6. 發送架構

```
Raft Leader
    │
    │  onStateMachineApply()
    ▼
LedgerStateMachine.apply(command)
    │
    ├── 更新 in-memory balance（含 accountSeq 遞增）
    ├── 寫 RocksDB WriteBatch（Journal + Balance）
    │
    └── 構建 BalanceChangeEvent（含 accountSeq / prevAccountSeq）
            │
            ▼
    OutboxStore（RocksDB CF_OUTBOX）
            │
    AsyncKafkaPublisher（Virtual Thread）
            │
            ▼
    Kafka Topic: ledger.balance.change.v1
```

### 6.1 Outbox 模式（確保 at-least-once）

```
1. State Machine apply() 時，將 BalanceChangeEvent 寫入
   RocksDB CF_OUTBOX（同一 WriteBatch，與 balance 更新原子）
   BalanceChangeEvent 在 apply 時已確定 accountSeq，重發不改變

2. AsyncKafkaPublisher 後台線程：
   a. 掃描 CF_OUTBOX 未發送事件
   b. 發送到 Kafka
   c. 發送成功 → 從 CF_OUTBOX 刪除（或標記 SENT）
   d. 發送失敗 → 指數退避重試，最多 10 次

3. 重啟恢復：
   a. 節點重啟後，AsyncKafkaPublisher 重新掃描 CF_OUTBOX
   b. 未發送的事件繼續投遞，accountSeq 與原始一致
   c. 下游按 idempotencyKey 去重（重發事件 accountSeq 相同，不是 gap）
```

### 6.2 發送保證

| 保證 | 實現方式 |
|---|---|
| **不丟失**（Outbox 原子寫） | BalanceChangeEvent 與 Balance 更新同一 RocksDB WriteBatch |
| **At-least-once** | Outbox 重試機制，失敗繼續重試 |
| **不阻塞主路徑** | Kafka publish 異步執行，失敗不影響 Posting 返回 |
| **順序性（同帳戶同 type）** | Partition key = accountId:balanceType:currency |
| **下游冪等** | 每個事件帶唯一 `idempotencyKey`，下游自行去重 |
| **下游 gap 偵測** | `accountSeq` / `prevAccountSeq` 供下游驗證 stream 完整性 |

---

## 7. 多 Balance Type 場景示例

以 RFQ 場景為例，一次 Posting 涉及 2 個帳戶：

```
Posting：CLIENT_ACC_001 AVAILABLE_BALANCE/USD DEBIT 800
         COMPANY_FX_ACC AVAILABLE_BALANCE/USD CREDIT 800
```

Kafka 發出 **2 個獨立事件**（假設各自上一條 seq 為 41 和 99）：

```json
// Event 1
{
  "accountId": "CLIENT_ACC_001",
  "balanceType": "AVAILABLE_BALANCE",
  "currency": "USD",
  "entryType": "DEBIT",
  "amount": "800.00",
  "preBalance": "1000.00",
  "postBalance": "200.00",
  "balanceDelta": "-800.00",
  "accountSeq": 42,
  "prevAccountSeq": 41,
  "idempotencyKey": "req-001:CLIENT_ACC_001:AVAILABLE_BALANCE:USD"
}

// Event 2
{
  "accountId": "COMPANY_FX_ACC",
  "balanceType": "AVAILABLE_BALANCE",
  "currency": "USD",
  "entryType": "CREDIT",
  "amount": "800.00",
  "preBalance": "5000.00",
  "postBalance": "5800.00",
  "balanceDelta": "800.00",
  "accountSeq": 100,
  "prevAccountSeq": 99,
  "idempotencyKey": "req-001:COMPANY_FX_ACC:AVAILABLE_BALANCE:USD"
}
```

如果同一 Journal 同時更新 `AVAILABLE_BALANCE` 和 `TRADE_AHEAD_BALANCE`，則每個 balance type 各發一個事件，各自維護獨立的 `accountSeq`。

---

## 8. Schema Evolution 策略

- `eventVersion` 採用語意版本（`1.0`、`1.1`、`2.0`）
- **v0.2 本次變更**：`1.0` → `1.1`（backward compatible minor change）
  - 新增 `accountSeq`、`prevAccountSeq` 兩個**可選欄位**
  - 下游舊版 consumer 可忽略新欄位，無需強制升級
- **Breaking change**（刪除或改欄位類型）：版本升 major（`1.x` → `2.0`），新舊 topic 並行一段時間
- 建議下游按 `eventVersion` 做 routing，不要硬編碼欄位

---

## 9. API / 配置

```yaml
# application.yml
ledger:
  kafka:
    bootstrap-servers: kafka1:9092,kafka2:9092,kafka3:9092
    topic: ledger.balance.change.v1
    partitions: 64
    acks: all               # 等待所有 ISR 確認
    retries: 10
    retry-backoff-ms: 500
    compression: lz4
    outbox:
      scan-interval-ms: 100
      max-retry: 10
      retry-backoff-multiplier: 2
```

---

## 10. 驗收標準

| # | 驗收條件 | 測試方式 |
|---|---|---|
| AC-01 | Posting 成功後，Kafka 收到對應 BalanceChangeEvent | 功能測試 |
| AC-02 | Event 的 preBalance、postBalance 與 State Machine 一致 | 功能測試 |
| AC-03 | 同一 Posting（多條 JournalLine）發出多個獨立事件，各自帶正確 balanceDelta | 功能測試 |
| AC-04 | Reversal 發出的事件 entryType 與原 Posting 相反 | 功能測試 |
| AC-05 | Manual Adjustment 入帳後發出事件，commandType=MANUAL_ADJUSTMENT | 功能測試 |
| AC-06 | Kafka publish 失敗，Posting 結果不受影響，事件從 Outbox 重試 | 故障注入測試 |
| AC-07 | 節點重啟後，Outbox 未發事件繼續投遞，不丟失 | 故障注入測試 |
| AC-08 | 相同 requestId 的 Posting 重試（冪等），只發一個事件 | 功能測試 |
| AC-09 | 同帳戶同 balanceType 的事件落在同一 partition，順序正確 | 功能測試 |
| AC-10 | Kafka publish P95 延遲（從 Raft commit 到 Kafka confirm）≤ 100ms | 性能測試 |
| AC-11 | 所有 balance type（包括 TRADE_AHEAD_BALANCE）的變動均發出事件 | 功能測試 |
| AC-12 | 同一 accountId + balanceType + currency 維度的 BalanceChangeEvent，accountSeq 嚴格單調遞增，不跳號、不重複 | 功能測試 |
| AC-13 | 系統重啟後，accountSeq 從 RocksDB / Snapshot 恢復，第一條新事件的 accountSeq = 重啟前最後一條 + 1，不得重置為 0 | 故障恢復測試 |
| AC-14 | 下游可透過 prevAccountSeq ≠ 上一條收到的 accountSeq 精確偵測 event stream gap，並觸發告警 | 下游整合測試 |

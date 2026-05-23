# F-005 v2 Balance Query & Snapshot（Raft 架構更新版）

**文件版本**: v0.3（新增 `position` 欄位 — CURRENT/LOCKED/FROZEN 子餘額）  
**功能**: F-005 Balance Query & Snapshot  
**系統**: Next-Gen Internal Ledger Platform  
**狀態**: Draft for Review  
**變更摘要**: 實時 Balance 查詢路徑由 MySQL 改為 in-memory State Machine；v0.3 新增 `position` 欄位支援子餘額追蹤（CURRENT/LOCKED/FROZEN）

---

## 1. 架構前提（依 ADR-001）

| 查詢類型 | 資料來源 | 延遲目標 |
|---|---|---|
| 實時餘額查詢 | Raft Leader in-memory State Machine | P95 ≤ 2ms |
| As-of 歷史快照查詢 | MySQL View Layer（Learner 同步） | P95 ≤ 30ms |
| EOD 快照查詢 | MySQL View Layer | P95 ≤ 30ms |
| Journal Replay（兜底） | MySQL journal_line | P95 ≤ 5s |

---

## 2. 實時 Balance 查詢

### 2.1 查詢路徑

```
Client → Ledger Service（Leader 節點）
                │
                ▼
    in-memory State Machine
    ConcurrentHashMap<AccountKey, BalanceMap>
                │
                ▼
         直接讀取返回
         不走網路、不走磁碟
         P95 < 0.5ms（讀取本身）
         + 網路延遲 ≈ 1–2ms
         = P95 ≤ 2ms ✅
```

**重要**：Balance 查詢必須路由到 **Raft Leader 節點**，讀 Follower 可能得到舊數據。

### 2.2 API

```
GET /ledger/balances
    ?accountId={accountId}
    &balanceType={balanceType}
    &currency={currency}
    [&position={position}]     // 可選：CURRENT, LOCKED, FROZEN
```

**查詢類型：**
- **不帶 position 參數**：返回所有 position 的匯總餘額
- **帶 position 參數**：返回單一 position 餘額

### 2.3 Response 結構

**匯總查詢（無 position 參數）：**

```json
{
  "accountId": "CLIENT_ACC_001",
  "balanceType": "BROKERAGE_BALANCE",
  "currency": "USD",
  "amount": 250000.00,
  "positions": {
    "CURRENT": 200000.00,
    "LOCKED": 50000.00,
    "FROZEN": 0.00
  },
  "allowNegative": false,
  "dataSource": "STATE_MACHINE"
}
```

**單一 Position 查詢（帶 position 參數）：**

```json
{
  "accountId": "CLIENT_ACC_001",
  "balanceType": "BROKERAGE_BALANCE",
  "currency": "USD",
  "amount": 50000.00,
  "positions": {
    "LOCKED": 50000.00
  },
  "allowNegative": false,
  "dataSource": "STATE_MACHINE"
}
```

---

## 3. 批量 Balance 查詢

```
POST /ledger/balances/batch
Body: [
  { "accountId": "A", "balanceType": "BROKERAGE_BALANCE", "position": "CURRENT", "currency": "USD" },
  { "accountId": "A", "balanceType": "BROKERAGE_BALANCE", "position": "LOCKED", "currency": "USD" }
]
```

**注意**：批量查詢的每個 key 必須包含 `position` 欄位。

| 指標 | v0.2（State Machine） |
|---|---|
| 100 帳戶批量查詢 P95 | ≤ 5ms |
| 200 帳戶批量查詢 P95 | ≤ 10ms |

---

## 4. State Machine 內部資料結構

```java
// In-memory State Machine 的 Balance 儲存結構
// Key: AccountBalanceKey = (accountId, balanceType, position, currency)
// Value: BalanceEntry

class BalanceEntry {
    BigDecimal amount;        // 當前餘額
    long stateVersion;        // 對應的 Raft Log Index
    String lastJournalId;     // 最後一筆 Journal ID
    Instant lastUpdatedAt;    // 最後更新時間
}

ConcurrentHashMap<AccountBalanceKey, BalanceEntry> balanceStore;
```

**Position 類型：**
- `CURRENT` — 可用餘額
- `LOCKED` — 鎖定餘額（如待結算交易）
- `FROZEN` — 凍結餘額（如合規凍結）

**注意**：LOCKED 和 FROZEN position 不允許負數餘額（在 State Machine 層強制）。

---

## 5. As-of 歷史快照查詢（架構不變，資料來源調整）

As-of 查詢讀 MySQL View Layer（Learner 已同步的資料）：

```
查詢邏輯優先級:

1. 從 MySQL balance_snapshot 找 asOf 時間點前最近的快照
   ├─ 找到 → 返回，標記 dataSource=EOD_SNAPSHOT
   └─ 找不到 → 從 MySQL journal_line 做 Journal Replay

注意：As-of 查詢不讀 in-memory State Machine
因為 State Machine 只保存「當前」狀態
歷史狀態在 MySQL View Layer 或 RocksDB snapshot 中
```

---

## 6. EOD Snapshot 機制

### 6.1 快照生成方式（更新）

v0.2 的 EOD Snapshot 有兩種來源：

**來源 A：State Machine Snapshot（推薦）**
- 由 Raft Leader 定期（或觸發）對 State Machine 做 snapshot
- Snapshot 包含所有帳戶、所有 balance type 的當前餘額
- 通過 Learner 同步到 MySQL balance_snapshot 表
- 優點：完全準確，與 in-memory 一致

**來源 B：Learner 增量同步後觸發**
- Learner 持續把 journal_line 同步到 MySQL
- 帳期關閉時，Learner 觸發 EOD Snapshot Job，從 MySQL journal_line 聚合生成快照
- 優點：不依賴 Leader，Learner 可獨立完成

### 6.2 快照觸發

```
觸發條件:
  1. 帳期關閉（F-009 AccountingPeriod 關期）
  2. 定時任務（每日 23:59）
  3. 手動觸發（管理 API）
  4. State Machine Snapshot（由 Raft 自動觸發，每 10 萬條 log）
```

---

## 7. 一致性保證

| 場景 | 一致性保證 |
|---|---|
| 實時 Balance 查詢（讀 State Machine） | **強一致性**：永遠是最新 committed 狀態 |
| As-of 快照查詢（讀 MySQL） | **最終一致性**：可能落後 Leader 最多 1 秒 |
| EOD Snapshot | **強一致性**：從 State Machine Snapshot 生成 |
| Reconciliation（讀 MySQL） | **最終一致性**，對帳允許分鐘級延遲 |

---

## 8. 驗收標準

| # | 驗收條件 | 測試方式 |
|---|---|---|
| AC-01 | 實時 Balance 查詢讀 in-memory State Machine，P95 ≤ 2ms | 性能測試 |
| AC-02 | Posting 完成後，下一次 Balance 查詢立即反映新餘額（無延遲） | 一致性測試 |
| AC-03 | `TRADE_AHEAD_BALANCE` 負數餘額正確返回，`dataSource=STATE_MACHINE` | 功能測試 |
| AC-04 | 批量查詢 200 帳戶，P95 ≤ 10ms | 性能測試 |
| AC-05 | As-of 查詢返回正確歷史餘額，`dataSource=EOD_SNAPSHOT` 或 `JOURNAL_REPLAY` | 功能測試 |
| AC-06 | Raft Leader 切換後，新 Leader 的 Balance 查詢結果與舊 Leader 一致 | 故障測試 |
| AC-07 | EOD Snapshot 與 State Machine Snapshot 餘額完全一致 | 對帳測試 |

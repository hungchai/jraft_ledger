# F-013 Idempotency & Hotspot Account Concurrency — 功能需求規格

**文件版本**: v0.1  
**功能**: F-013 Idempotency & Hotspot Account Concurrency  
**系統**: Next-Gen Internal Ledger Platform  
**狀態**: Draft for Review  
**依賴**: ADR-001、F-002 Posting API、F-003 Manual Adjustment、F-004 Reversal、F-008 State Machine、F-010 Account Management

---

## 1. 功能概述

本文件規範兩個緊密相關的核心機制：

1. **冪等性（Idempotency）**：確保同一筆帳務請求（Posting / Reversal / Adjustment / Account Create）無論重試多少次，系統只執行一次實際帳務變動，防止重複出金、重複入帳。
2. **熱點帳戶併發處理（Hotspot Account Concurrency）**：解決 RFQ 場景下 COMPANY_ACC 等高頻帳戶的併發衝突，以 Account-Level Queue 串行化請求，消除傳統 DB row lock 競爭。

---

## 2. Idempotency 機制

### 2.1 核心原則

- **所有寫操作必須冪等**：Posting、Reversal、Adjustment Approve、Account Create / Freeze / Close
- **冪等鍵**：`requestId`（UUID v7 推薦），由 caller 生成並攜帶於每次請求
- **冪等有效期**：24 小時（可配置），超過 TTL 後 idempotency record 可被淘汰
- **跨節點冪等**：idempotencyStore 隨 State Machine Snapshot 持久化到 RocksDB，新 Leader 恢復後仍識別歷史 requestId

### 2.2 Idempotency Store 設計

```java
record IdempotencyEntry(
    String requestId,
    String status,          // COMPLETED / REJECTED
    String journalId,       // 成功時的 journalId
    List<String> errors,    // 失敗時的錯誤碼列表
    Instant completedAt
) {}

// In-memory store：ConcurrentHashMap（無鎖讀，Account Worker 串行寫）
ConcurrentHashMap<String, IdempotencyEntry> idempotencyStore;
```

**雙層保障**：
- **L1**：in-memory `idempotencyStore` → P95 ≤ 0.5ms 命中
- **L2**：RocksDB `CF_IDEMPOTENCY` → State Machine 啟動時從 Snapshot 恢復

### 2.3 冪等檢查流程

```
Client Request (requestId = req-abc123)
      │
      ▼
┌─────────────────────────────────────────┐
│ 1. Network Layer 接收請求                │
│    → 反序列化 → 前置校驗                │
│    → 放入 request_queue                 │
└─────────────────┬───────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────┐
│ 2. Account Queue Worker 取出請求         │
│    → 冪等檢查（讀 idempotencyStore）     │
│                                         │
│    IF requestId EXISTS:                 │
│      ├─ status = COMPLETED              │
│      │   → 返回原結果（journalId 等）   │
│      │   → 不執行任何帳務變動           │
│      │                                   │
│      └─ status = REJECTED               │
│          → 返回原錯誤碼列表             │
│          → 不重新校驗                   │
│                                         │
│    IF requestId NOT EXISTS:             │
│      → 繼續執行後續校驗與帳務邏輯       │
└─────────────────┬───────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────┐
│ 3. State Machine apply()                 │
│    → 執行帳務變動（Journal + Balance）  │
│    → 寫 RocksDB WriteBatch              │
│    → idempotencyStore.put(requestId,    │
│       IdempotencyEntry(COMPLETED, ...)) │
│    → takeSnapshot()（含 idempotency）   │
└─────────────────┬───────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────┐
│ 4. Response 返回                         │
│    → 首次請求：正常返回結果               │
│    → 重試請求：返回快取結果（路徑相同）  │
└─────────────────────────────────────────┘
```

### 2.4 各寫路徑的冪等行為

| 操作 | 冪等鍵欄位 | 重複請求行為 | 錯誤碼 |
|---|---|---|---|
| Posting | `requestId` | 返回原 Journal（HTTP 200） | — |
| Reversal | `requestId` | 返回原 Reversal Journal（HTTP 200） | — |
| Adjustment Approve | `requestId` | 返回原 Adjustment Journal（HTTP 200） | — |
| Account Create | `requestId` | 返回原 Account（HTTP 200） | — |
| Account Freeze | `requestId` | 返回原操作結果（HTTP 200） | — |

**注意**：
- Adjustment **Draft 創建**（Maker 提交）不走 Raft，冪等由 MySQL `adjustments_draft` 表的 `draftRequestId` UNIQUE constraint 保障
- 冪等記錄在 Raft Leader 切換後仍然有效（從 RocksDB Snapshot 恢復）

### 2.5 Raft Leader 故障切換後的冪等

```
場景：Client 發送 Posting → Leader 收到 → apply 完成 → 返回 Response 前 Leader 宕機
       → Client 超時重試 → 新 Leader 選出

新 Leader 行為：
  1. 從 RocksDB CF_SM_SNAPSHOT 恢復 State Machine
     → idempotencyStore 包含 req-abc123 → COMPLETED
  2. Client 重試到達新 Leader
  3. Account Queue Worker 檢查 idempotencyStore
     → 命中 → 返回原結果 → 不重新執行

結果：無重複入帳，client 最終收到成功結果
```

### 2.6 Idempotency TTL 與 Eviction

```
目的：防止 idempotencyStore 無限增長（百萬級 requestId）

策略：
  - 預設 TTL：24 小時（配置項 `ledger.idempotency.ttl-hours`）
  - Eviction Job：每小時掃描一次，刪除 completedAt + TTL < now 的記錄
  - RocksDB：CF_IDEMPOTENCY 使用 TTL column family，自動過期

安全邊界：
  - 24 小時內重試 → 冪等生效
  - 超過 24 小時後重試相同 requestId → 視為新請求，可能重複執行
  - 業務方應確保重試在合理時間窗內完成
```

### 2.7 冪等與併發的關係

```
場景：兩個线程同時發送相同 requestId

處理：
  Thread-A 先到達 Account Queue → 檢查 idempotencyStore → 未命中
  Thread-A 進入 State Machine apply()

  Thread-B 同時到達 Account Queue → 檢查 idempotencyStore → 未命中
  （因為 Thread-A 尚未完成 apply）

  Thread-A 完成 apply → idempotencyStore.put(requestId, COMPLETED)
  Thread-B 進入 apply → 再次檢查 idempotencyStore → 命中 COMPLETED
  Thread-B 返回快取結果

  結果：只執行一次帳務變動（Thread-A），Thread-B 返回相同結果

關鍵保證：Account Queue 是單線程串行，因此上述競態不會發生於同一帳戶。
多帳戶場景（RFQ）的競態由 Multi-Account Coordinator 的協調票機制處理。
```

---

## 3. Hotspot Account 併發處理

### 3.1 問題定義

```
RFQ 場景：
  CLIENT_ACC_001 ──→ COMPANY_FX_ACC
  CLIENT_ACC_002 ──→ COMPANY_FX_ACC
  CLIENT_ACC_003 ──→ COMPANY_FX_ACC
  ...
  所有客戶的 RFQ 成交對手方都是同一個 COMPANY_FX_ACC

傳統方案問題：
  - 多線程同時 UPDATE COMPANY_FX_ACC balance → DB row lock 競爭
  - 高併發下鎖等待時間線性增長，P95 無法達標
  - 鎖升級、死鎖風險
```

### 3.2 解決方案：Account-Level Queue

```
每個 accountId 維護一條獨立的 LinkedBlockingQueue：

AccountQueueManager
  ├── queue("CLIENT_ACC_001") → Virtual Thread Worker-1
  ├── queue("CLIENT_ACC_002") → Virtual Thread Worker-2
  ├── queue("COMPANY_FX_ACC") → Virtual Thread Worker-3   ← hotspot
  ├── queue("CLIENT_ACC_003") → Virtual Thread Worker-4
  └── ...

每條 queue 特性：
  - 單一 Virtual Thread 消費（Java 21 Virtual Threads）
  - 同一帳戶的所有請求嚴格串行執行
  - 不同帳戶的 queue 並行執行（無相互阻塞）
```

### 3.3 為什麼消除鎖競爭

```
傳統 DB row lock：
  Thread-A ──→ UPDATE COMPANY_FX_ACC balance = balance + 100
  Thread-B ──→ UPDATE COMPANY_FX_ACC balance = balance - 200
  Thread-C ──→ UPDATE COMPANY_FX_ACC balance = balance + 300
  → 三個线程競爭同一 row lock，後到者等待

Account-Level Queue：
  Thread-A ──→ enqueue to queue("COMPANY_FX_ACC")
  Thread-B ──→ enqueue to queue("COMPANY_FX_ACC")
  Thread-C ──→ enqueue to queue("COMPANY_FX_ACC")

  Worker-3（單一 Virtual Thread）：
    1. 取出 Thread-A 請求 → apply → balance + 100
    2. 取出 Thread-B 請求 → apply → balance - 200
    3. 取出 Thread-C 請求 → apply → balance + 300

  → 無鎖競爭，只有 nanosecond 級的 queue 入隊/出隊延遲
  → 所有 COMPANY_FX_ACC 操作在記憶體中串行完成
```

### 3.4 Multi-Account 原子協調（RFQ 場景）

```
問題：RFQ 涉及兩個帳戶（CLIENT_ACC + COMPANY_ACC），如何保證原子？

解法：Multi-Account RaftCommand Coordinator

Phase 1 — 取得協調票（按 accountId 升序，避免死鎖）：
  1. 收集所有涉及的 accountId
  2. 按字典序升序排列（CLIENT_ACC_001 < COMPANY_FX_ACC）
  3. 依序在每個 Account Queue 中取得「Coordination Token」
  4. 所有 queue 都取得 Token 後，進入 Phase 2

Phase 2 — 構建並提交 Multi-Account RaftCommand：
  1. 構建單一 RaftCommand，包含所有 JournalLine
  2. 提交到 Raft Group → Quorum commit
  3. State Machine apply 時，一次性更新所有帳戶的 in-memory balance
  4. 視為原子操作：全部成功或全部失敗

Phase 3 — 釋放 Token：
  1. 按升序釋放各 Account Queue 的 Coordination Token
  2. 其他請求可繼續執行

死鎖預防：
  - 關鍵：所有 Multi-Account 操作按「相同順序」取得 Token
  - 若兩個 RFQ 同時涉及 A+B，兩者都按 A→B 順序取 Token
  - 不會出現 RFQ-1 取 A、RFQ-2 取 B 的循環等待
```

### 3.5 Back-Pressure（背壓）

```
當 Account Queue 深度超過閾值時，拒絕新請求：

配置項：
  ledger.account-queue.max-size = 10,000（默認）

行為：
  IF queue("COMPANY_FX_ACC").size() >= MAX_QUEUE_SIZE:
    → 返回 HTTP 429 QUEUE_FULL
    → caller 應指數退避重試

目的：
  - 防止記憶體無限增長（OOM）
  - 防止極端併發下單一 queue 積壓過長
  - 提示 caller 降速

監控：
  - Metric: ledger.state_machine.queue.depth（Gauge，accountId 維度）
  - Alert: queue depth > 5,000 持續 2 分鐘 → PagerDuty
```

### 3.6 性能對比

| 場景 | 傳統 DB Row Lock | Account-Level Queue |
|---|---|---|
| 單一 hotspot 帳戶 1000 QPS | P95 ≈ 50–200ms（鎖等待） | P95 ≤ 3ms（純記憶體） |
| 鎖競爭 | 高（線性增長） | 無（串行化） |
| 死鎖風險 | 存在（需檢測/超時） | 不存在（單線程） |
| 擴展性 | 垂直擴展（DB CPU） | 水平擴展（增加節點） |
| 記憶體開銷 | 低 | 每帳戶一條 queue（~KB 級） |

### 3.7 Virtual Thread 調度

```java
// 每條 Account Queue 對應一條 Virtual Thread
Thread.startVirtualThread(() -> {
    while (running) {
        Command cmd = queue.take();  // 阻塞等待
        try {
            stateMachine.apply(cmd);   // 串行執行
        } catch (Exception e) {
            logger.error("Apply failed", e);
        }
    }
});
```

**設計理由**：
- Virtual Thread 輕量（~KB 級堆疊），可創建數十萬條
- 阻塞操作（queue.take()）不佔用 OS thread，由 JVM 調度
- 與 Platform Thread 相比，上下文切換成本極低

---

## 4. Idempotency + Hotspot 的協同效應

```
完整 RFQ 併發 + 重試場景：

Step 1: Client 發送 RFQ Posting (requestId = req-001)
         → 到達 Leader → enqueue to queue("CLIENT_ACC_001")
         → enqueue to queue("COMPANY_FX_ACC")

Step 2: 同時另一 Client 發送 RFQ Posting (requestId = req-002)
         → enqueue to queue("CLIENT_ACC_002")
         → enqueue to queue("COMPANY_FX_ACC")

Step 3: queue("COMPANY_FX_ACC") Worker 串行處理：
         先執行 req-001（更新 COMPANY_FX_ACC balance）
         再執行 req-002（更新 COMPANY_FX_ACC balance）
         → 無鎖競爭，無資料競爭

Step 4: Client 因網路超時重試 req-001（相同 requestId）
         → idempotencyStore 命中 → 返回原結果
         → 不重複更新 COMPANY_FX_ACC

結果：
  - 併發安全：Account Queue 串行化保證
  - 冪等安全：idempotencyStore 保證
  - 性能達標：P95 ≤ 3ms
```

---

## 5. API / 配置

```yaml
# application.yml
ledger:
  idempotency:
    ttl-hours: 24           # 冪等記錄保留時間
    eviction-interval-min: 60  # 清理間隔
  account-queue:
    max-size: 10000         # 單一 queue 最大深度
    virtual-thread-pool:    # Virtual Thread 調度器配置
      max-virtual-threads: 100000
  multi-account:
    coordination-timeout-ms: 5000  # 取 Token 超時（防死鎖兜底）
```

---

## 6. 驗收標準

| # | 驗收條件 | 測試方式 |
|---|---|---|
| AC-01 | 相同 requestId 重試 1000 次，只生成 1 筆 Journal | 冪等測試 |
| AC-02 | 冪等重試在 idempotencyStore 命中，P95 ≤ 1ms | 性能測試 |
| AC-03 | Raft Leader 切換後，重試歷史 requestId 仍返回原結果（不重新執行） | 故障恢復測試 |
| AC-04 | 1000 並發 RFQ 打同一 COMPANY_ACC，無重複出金、無餘額不一致 | 並發安全測試 |
| AC-05 | 1000 並發 RFQ 打同一 COMPANY_ACC，Posting P95 ≤ 3ms | 性能測試 |
| AC-06 | Account Queue 深度超過 MAX_QUEUE_SIZE 時返回 QUEUE_FULL | 背壓測試 |
| AC-07 | Multi-Account 協調按 accountId 升序取 Token，無死鎖 | 死鎖壓力測試 |
| AC-08 | idempotencyStore 超過 TTL 後自動淘汰，新請求視為首次執行 | TTL 測試 |
| AC-09 | Virtual Thread Worker 崩潰後，Account Queue 自動重建並恢復消費 | 故障注入測試 |
| AC-10 | 兩個同時到達的相同 requestId，只執行一次帳務變動 | 競態條件測試 |

---

# ADR-001 Ledger 核心架構決策：Raft + CQRS + Account-Level Queue

**決策狀態**: Accepted
**決策日期**: 2026-05-16
**決策人**: Ledger Platform Team
**影響範圍**: F-002 Posting, F-003 Manual Adjustment, F-004 Reversal, F-005 Balance Query, F-006 Journal Query, F-007 Reconciliation, F-008 State Machine

> **v0.2 變更摘要**：Section 3.2 補充 Multi-Account Coordinator 完整實現規格（Leader 選舉機制、Timeout 處理、N 帳戶通用設計）；新增 Section 3.3 Multi-Account Task 資料結構；Section 8 新增風險項。

---

## 1. 背景與問題

Internal Ledger Platform 需要同時滿足以下三個互相衝突的要求：

1. **同步原子落帳**：每筆 Posting / Reversal / Adjustment 必須完全原子，不允許異步落帳
2. **RFQ 公司帳戶 Hotspot**：所有客戶 RFQ 成交對手方為同一公司帳戶，傳統 DB row lock 在高並發下會造成嚴重鎖等待
3. **極低延遲**：Posting P95 ≤ 3ms、Balance Query P95 ≤ 2ms

傳統 Spring Boot 多節點 + PostgreSQL 方案在 hotspot 帳戶下無法同時滿足原子性和低延遲；Shard / Rebalancing 方案引入資金管理複雜性。

---

## 2. 決策

採用 **Raft + CQRS + Account-Level In-Memory Queue** 架構，參考 Binance Ledger 生產方案。

### 2.1 核心原則

- **寫路徑**：所有帳務寫操作（Posting / Reversal / Adjustment）通過 Raft 共識協議，由 Leader 節點的 in-memory State Machine 串行處理，持久化到 RocksDB，不直接寫 MySQL
- **讀路徑**：餘額查詢直接讀 Leader in-memory State Machine；Journal / 對帳查詢讀 MySQL View Layer
- **同步機制**：Raft Learner 節點監聽 Raft Log，異步同步到 MySQL，供查詢與對帳使用
- **帳戶序列化**：每個帳戶維護一條 in-memory queue，由單一 virtual thread 串行處理，消除帳戶級並發衝突

### 2.2 Raft 庫選型

採用 **SOFAJRaft**（Ant Group / Alibaba 開源），理由：
- Java 原生，與 Spring Boot 集成良好
- 支持 Multi-Raft-Group，可按帳戶分組提升水平擴展能力
- 生產驗證（Ant Financial 同款技術棧）
- 支持 Learner 角色，適用於 CQRS 讀寫分離

### 2.3 整體架構

```
Client Request
      │
      ▼
┌─────────────────────────────────────────────────┐
│              Ledger Write Domain                 │
│                                                  │
│  ┌────────────┐   ┌──────────────────────────┐  │
│  │   Network  │   │       Raft Cluster        │  │
│  │   Layer    │   │  ┌────────┐  ┌─────────┐ │  │
│  │ (gRPC/HTTP)│   │  │ Leader │  │Follower │ │  │
│  └─────┬──────┘   │  │        │  │         │ │  │
│        │          │  │RocksDB │  │ RocksDB │ │  │
│  ┌─────▼──────┐   │  └────────┘  └─────────┘ │  │
│  │   Ledger   │◄──►       ↕ Raft Log          │  │
│  │   Layer    │   │  ┌─────────┐              │  │
│  │            │   │  │ Learner │ non-voting   │  │
│  │  Account   │   │  └────┬────┘              │  │
│  │  Queue     │   └───────│───────────────────┘  │
│  │  (per acct)│           │ async push            │
│  │            │           ▼                      │
│  │  State     │   ┌───────────────┐              │
│  │  Machine   │   │  View Layer   │              │
│  │ (in-memory │   │  (MySQL)      │              │
│  │  balance)  │   │  journal_line │              │
│  └────────────┘   │  account_bal  │              │
│                   │  snapshot     │              │
└───────────────────┴───────────────┴──────────────┘
         ↑ Write                  ↑ Read
    Posting / Rev / Adj      Journal / Recon / Report
```

---

## 3. 寫路徑詳述

### 3.1 請求流水線

```
1. Network Layer     接收 HTTP/gRPC 請求，反序列化，放入 request_queue
2. Ledger Layer      從 request_queue 取出，做前置校驗（schema / auth）
                     按 accountId 路由到對應 Account Queue
3. Account Queue     每個帳戶一條 LinkedBlockingQueue
                     每條 queue 對應一條 Java 21 Virtual Thread（worker）
4. Account Worker    從 queue 取出任務，串行執行：
                     a. 冪等檢查（in-memory idempotency map）
                     b. Balance 校驗（讀 in-memory State Machine）
                     c. 準備 Raft Command（序列化帳務指令）
5. Raft Layer        提交 Command 到 Raft Group
                     Leader 複製到 Follower，達到 Quorum 後 commit
6. State Machine     apply Raft log，執行帳務計算：
                     a. 更新 in-memory balance
                     b. 生成 journal_line 記錄
                     c. 寫 RocksDB（持久化）
7. Response          通過 response_queue 返回結果給 client
```

### 3.2 多帳戶 Journal 的原子性【v0.2 詳化】

RFQ 場景涉及 CLIENT_ACC + COMPANY_ACC 兩個（或以上）帳戶。

#### 核心設計原則：Queue 即 Lock

每個帳戶一條 `LinkedBlockingQueue`，由單一 Virtual Thread 串行消費。**沒有顯式 Lock / Mutex / synchronized**。Queue 本身保證同一帳戶在任意時刻只有一筆交易在執行，鎖即隊列位置。

#### 死鎖預防：accountId 升序排列

多帳戶場景必須按 accountId **字典序升序**依次提交 task，確保所有請求以相同順序獲取「Queue 位置」，消除循環等待（Resource Ordering 算法）：

```
✅ 正確：所有請求先佔 COMPANY_FX_ACC queue，再佔 CLIENT_ACC_001 queue
❌ 錯誤：不排序 → 可能互相等待 → 死鎖
```

#### Two-Phase 協調流程

```
Phase 1 – 佔位（Coordinator 負責）
  1. 提取所有涉及 accountId，按字典序升序排列
  2. 建立 MultiAccountTask（含 CountDownLatch + AtomicInteger + resultLatch）
  3. 依序把同一個 MultiAccountTask 推入各帳戶的 Account Queue
     → Queue 滿（> 1000）時立即返回 HTTP 429，不阻塞

Phase 2 – 執行（Account Worker 負責）
  每個 Account Worker 取出 MultiAccountTask 後：
  1. 調用 task.markReadyAndCheckLeader()
     → AtomicInteger 遞增，返回「是否最後一個 ready」
  2. 調用 task.readyLatch.await(timeout=30ms)
     → 等待所有帳戶 Worker 都 ready
     → 超時 → 整筆請求 TIMEOUT_WAITING_ACCOUNTS，見 Timeout 處理
  3. 若 isLeader：提交 RaftCommand，拿到結果，task.setResult()，task.resultLatch.countDown()
     若非 Leader：task.resultLatch.await(timeout=100ms)，拿 task.getResult()
  4. 返回結果給 caller response_queue
```

#### Leader Worker 選舉：固定為排序後最後一個帳戶的 Worker

```java
// Coordinator 建立 task 時確定，行為完全確定、易於追蹤
class MultiAccountTask {
    private final String leaderAccountId;  // sortedAccounts 的最後一個

    MultiAccountTask(List<String> sortedAccounts, RaftCommand command) {
        // 例：[COMPANY_FX_ACC, CLIENT_ACC_001] → leader = CLIENT_ACC_001
        this.leaderAccountId = sortedAccounts.get(sortedAccounts.size() - 1);
        this.totalAccounts   = sortedAccounts.size();
        this.readyLatch      = new CountDownLatch(totalAccounts);
        this.resultLatch     = new CountDownLatch(1);
        this.readyCount      = new AtomicInteger(0);
        this.command         = command;
    }

    // 每個 Worker 調用一次，返回 true = 我是 Leader
    boolean markReadyAndCheckLeader(String myAccountId) {
        readyCount.incrementAndGet();
        readyLatch.countDown();
        return myAccountId.equals(leaderAccountId);
    }
}
```

#### Timeout 處理

```
readyLatch.await 超時（預設 30ms）：
  原因：某帳戶 Queue 積壓嚴重，Worker 遲遲無法取出 task
  處理：
    1. 先 ready 的 Worker 調用 task.cancel()
    2. 所有 Worker 收到 cancelled 信號，立即返回 TIMEOUT_WAITING_ACCOUNTS
    3. 從各自的 Queue 中標記此 task 已作廢（後來的 Worker 取出後直接跳過）
    4. Coordinator 返回 HTTP 503，caller 可憑 requestId 重試（冪等保障）

resultLatch.await 超時（預設 100ms）：
  原因：Raft commit 超時（Leader 宕機 / 網絡分區）
  處理：
    1. 非 Leader Worker 返回 RAFT_TIMEOUT
    2. Coordinator 返回 HTTP 503，caller 重試
    3. 冪等機制保證重試安全（requestId 相同，不重複入帳）
```

### 3.3 MultiAccountTask 資料結構【v0.2 新增】

```java
class MultiAccountTask {
    // 輸入
    final String              requestId;
    final RaftCommand         command;
    final String              leaderAccountId;   // 排序後最後一個 accountId
    final int                 totalAccounts;

    // 協調
    final CountDownLatch      readyLatch;        // 等所有 Worker ready
    final CountDownLatch      resultLatch;       // 等 Leader 放結果
    final AtomicInteger       readyCount;        // 已 ready 的 Worker 數
    volatile boolean          cancelled = false; // timeout 取消標誌

    // 結果（Leader 寫入，其他 Worker 讀取）
    volatile CommandResult    result;

    // Worker 調用：標記自己 ready，返回是否是 Leader
    boolean markReadyAndCheckLeader(String myAccountId) {
        readyCount.incrementAndGet();
        readyLatch.countDown();
        return myAccountId.equals(leaderAccountId);
    }

    void setResult(CommandResult r) {
        this.result = r;
        resultLatch.countDown();
    }

    CommandResult getResult() throws InterruptedException, TimeoutException {
        if (!resultLatch.await(100, MILLISECONDS)) {
            throw new TimeoutException("Raft result timeout");
        }
        return result;
    }

    void cancel() {
        this.cancelled = true;
        // 釋放所有等待中的 await，讓 Worker 儘快退出
        while (readyLatch.getCount() > 0)  readyLatch.countDown();
        while (resultLatch.getCount() > 0) resultLatch.countDown();
    }
}
```

---

## 4. 讀路徑詳述

### 4.1 Balance Query

- 直接讀 Leader 的 in-memory State Machine
- 不走 RocksDB，不走 MySQL
- P95 目標：≤ 2ms（純記憶體讀取）

### 4.2 Journal / Audit / Reconciliation Query

- 讀 MySQL View Layer（由 Learner 異步同步）
- 可能有輕微延遲（通常 < 1 秒）
- P95 目標：≤ 100ms

### 4.3 一致性保證

- Balance Query 是強一致性（讀 Leader in-memory，永遠最新）
- Journal Query 是最終一致性（Learner 異步同步，可能略有延遲）
- 對帳場景允許最終一致性（T+0 對帳允許分鐘級延遲）

---

## 5. 高可用策略

### 5.1 Raft Cluster 配置

```
標準配置：3 節點（1 Leader + 2 Follower）
高可用配置：5 節點（1 Leader + 2 Follower + 2 Learner）

Quorum = (N+1)/2：
  3 節點 → Quorum = 2，允許 1 節點故障
  5 節點 → Quorum = 3，允許 2 節點故障

Learner 不參與投票，不影響 Quorum 計算
```

### 5.2 Leader 故障恢復

```
Leader 宕機 → Raft 自動選舉新 Leader
選舉時間：通常 150–300ms（SOFAJRaft 預設）
新 Leader 從 RocksDB + Raft Log replay 恢復 State Machine
恢復時間：取決於上一次 snapshot 後的 log 數量
→ 需定期做 State Machine Snapshot，控制 replay 時間
```

### 5.3 In-flight 請求處理

- Leader 切換時，in-flight 的 Raft Command 若未達 Quorum，client 會收到錯誤
- Client 憑藉 idempotencyKey 重試，新 Leader 正常處理（冪等保障）

---

## 6. 持久化策略

### 6.1 RocksDB（寫域）

- 儲存 Raft Log + State Machine Snapshot
- 每筆 journal_line 以 WAL 形式寫入，確保宕機可 replay
- 定期做 State Machine Snapshot，防止 Raft Log 無限增長

### 6.2 MySQL（View Layer）

- 由 Learner 異步寫入
- 儲存完整 journal、account_balance snapshot、reconciliation 報表
- 只做讀取用途，不在寫路徑上

### 6.3 資料保證

```
RocksDB：強持久性，是帳務的唯一真相（source of truth）
MySQL：最終一致性的查詢視圖，可從 RocksDB replay 重建
```

---

## 7. 技術約束

| 約束 | 說明 |
|---|---|
| 所有帳務寫操作必須走 Raft | 禁止直接寫 MySQL 繞過 Raft |
| Balance 查詢必須讀 in-memory State Machine | 禁止讀 MySQL balance（可能落後） |
| Account Worker 必須是單線程串行 | 禁止在同一帳戶的多個 journal 並發執行 |
| 多帳戶 task 必須按 accountId 升序排列 | 防止死鎖，所有路徑強制執行 |
| 不使用 ORM | 禁止 Hibernate / JPA，RocksDB 用 RocksDB Java API，MySQL 用 MyBatis |
| State Machine Snapshot 間隔 ≤ 10 萬條 Raft Log | 控制故障恢復時間 |

---

## 8. 風險與緩解

| 風險 | 嚴重度 | 緩解方案 |
|---|---|---|
| Leader 單點吞吐上限 | 中 | Multi-Raft-Group 按帳戶分組水平擴展 |
| State Machine 記憶體不足 | 中 | 只保留 active 帳戶 balance，冷帳戶按需從 RocksDB 載入 |
| Learner 同步延遲導致 Journal 查詢不一致 | 低 | 標明查詢時間戳，提示「最終一致性」 |
| RocksDB 損壞 | 低 | 多副本 Raft，任一 Follower 可做資料恢復 |
| SOFAJRaft 版本停更風險 | 低 | 評估 Apache Ratis 作為備選 |
| **Multi-Account readyLatch timeout（Queue 積壓）** | 中 | 單帳戶 Queue 容量 ≤ 1000，超限 429 快速失敗；30ms readyLatch timeout 保護不無限等待；caller 憑 requestId 重試 |
| **Leader Worker Raft timeout（宕機 / 網絡分區）** | 中 | 100ms resultLatch timeout；caller 憑 requestId 重試；冪等保障不重複入帳 |

---

## 9. 替代方案考慮

| 方案 | 放棄原因 |
|---|---|
| 傳統 Spring Boot + PostgreSQL row lock | Hotspot 帳戶下 P95 無法達標 |
| Shard + Rebalancing | 資金管理複雜，rebalancing 引入資金缺口風險 |
| Pre-authorized Limit + 異步 Settlement | 不符合「同步原子落帳」要求 |
| Redis 作 balance cache | 一致性風險不可接受（金融場景） |
| LMAX Disruptor | 解決 thread messaging 延遲，不解決 Raft network round-trip；busy-spin 模型與 per-account 百萬 queue 不相容 |
| `synchronized` / `ReentrantLock` 帳戶鎖 | 持有鎖期間 OS thread block，浪費 CPU；Virtual Thread park/unpark 配合 Queue 天然序列化，無需顯式鎖 |

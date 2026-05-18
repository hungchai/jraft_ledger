# ADR-001 Ledger 核心架構決策：Raft + CQRS + Account-Level Queue

**決策狀態**: Accepted  
**決策日期**: 2026-05-16  
**決策人**: Ledger Platform Team  
**影響範圍**: F-002 Posting, F-003 Manual Adjustment, F-004 Reversal, F-005 Balance Query, F-006 Journal Query, F-007 Reconciliation, F-008 State Machine

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

### 2.3 整體架構 (v0.3 — 新增 Projection Service)

```
                          POST /ledger/postings
                                │
               ┌────────────────┼────────────────┐
               ▼                ▼                ▼
         ledger-node-1     ledger-node-2     ledger-node-3
         (Leader)           (Follower)        (Follower)
         StateMachine       StateMachine      StateMachine
         RocksDB            RocksDB           RocksDB
               │                │                │
               └────────┬───────┘                │
                        │ Raft Log               │
                        ▼                        │
                 ┌──────────────┐                │
                 │    Kafka     │                │
                 │  Event Bus   │                │
                 └──────┬───────┘                │
                        │                        │
                        ▼                        │
                 ┌──────────────────────────────┐│
                 │  Projection Service :8089     ││
                 │  (Kafka Consumer → MySQL)     ││
                 └──────────────┬───────────────┘│
                                │                │
                                ▼                │
                         ┌──────────────┐        │
                         │  MySQL :3306 │        │
                         │ (View Layer) │◄───────┘
                         └──────────────┘  Journal/Recon reads

    Writes:  Client → Leader → StateMachine → RocksDB + Kafka
    Reads:   Balance → Any node (in-memory StateMachine)
             Journal → MySQL (via Projection Service)
```

> **v0.3 變更摘要**：架構圖新增 Kafka Event Bus + Projection Service。Learner 同步 MySQL 路徑替換為 Kafka → Projection Service 模式，實現完整 CQRS 讀寫分離。

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

### 3.2 多帳戶 Journal 的原子性

RFQ 場景涉及 CLIENT_ACC + COMPANY_ACC 兩個帳戶：

```
問題：兩個帳戶可能在不同 Account Queue，如何保證原子？

解法：Two-Phase Locking via Account Queue Coordinator

Phase 1 – Lock（按 accountId 升序排列，避免死鎖）:
  1. 把 CLIENT_ACC 和 COMPANY_ACC 按 ID 升序排列
  2. 依序提交到各自的 Account Queue 並取得「鎖票」
  3. 兩個 queue 都就緒後，一起提交一筆 Multi-Account Raft Command

Phase 2 – Apply（單一 Raft Command）:
  一筆 Raft Command 包含所有 JournalLine
  State Machine apply 時，一次性更新所有帳戶 in-memory balance
  → 對外表現為原子
```

---

## 4. 讀路徑詳述

### 4.1 Balance Query (CQRS Read Path)

- **任何 Raft 節點均可服務 Balance 查詢**（非 Leader 專屬）
- 讀取本機 in-memory State Machine（Raft log 已複製到所有節點）
- 不走 RocksDB，不走 MySQL
- P95 目標：≤ 2ms（純記憶體讀取）
- 一致性：最終一致性（Follower 可能落後 Leader 數毫秒，為 Raft log apply 延遲）

### 4.2 Journal / Audit / Reconciliation Query

- 讀 MySQL View Layer（由 Learner 異步同步）
- 可能有輕微延遲（通常 < 1 秒）
- P95 目標：≤ 100ms

### 4.3 CQRS 讀寫分離

```
寫路徑（僅 Leader）:
  Client → ANY node → forward to Leader → Raft → StateMachine → RocksDB

讀路徑（任何節點）:
  Balance Query  → 本機 in-memory StateMachine（Raft 已複製）
  Journal Query  → MySQL View Layer（Learner 異步同步）
```

| 操作 | 可服務節點 | 一致性 |
|---|---|---|
| Posting / Reversal / Adjustment | Leader only | 強一致性（Raft Quorum） |
| Balance Query | 任何節點 | 最終一致性（Raft log apply 延遲，通常 < 5ms） |
| Journal Query | 任何節點 | 最終一致性（Learner 同步，通常 < 1s） |
| Reconciliation | 任何節點 | 最終一致性（T+0 日內完成） |

---

## 4.4 Projection Service（CQRS View Layer Sync）【v0.3 新增】

Projection Service 是獨立的 Kafka 消費者服務，將帳務事件非同步投影到 MySQL View Layer：

```
Kafka Topic: ledger.balance.change.v1
       │
       ▼
ProjectionConsumer.onBalanceChange(message)
       │
       ├── 1. 反序列化 BalanceChangeEvent JSON
       ├── 2. INSERT INTO journal (idempotent: ON DUPLICATE KEY skip)
       ├── 3. INSERT INTO journal_line
       └── 4. 日誌記錄
```

**設計決策**：

| 決策 | 理由 |
|---|---|
| 獨立部署（非嵌入 Ledger node） | 解耦寫路徑與讀路徑；Projection crash 不影響入帳 |
| Kafka consumer group | 支援多實例水平擴展，同一 partition 內保證順序 |
| At-least-once + idempotent insert | Kafka 重複消費時，MySQL 唯一鍵保證不重複寫入 |
| 僅投影 journal / journal_line | Balance 查詢讀 in-memory StateMachine（強一致）；不需投影 balance 到 MySQL |

**為什麼不用 Raft Learner 直接寫 MySQL？**

Learner 同步方案需要 Learner 節點內嵌 MyBatis/DataSource，耦合 Raft 層與 DB 層。Kafka 解耦方案：
- 寫路徑不碰 MySQL（零延遲影響）
- Projection 可獨立擴展、重啟、升級
- 下游多個消費者（Risk Engine、VAMP、通知服務）可訂閱同一 Kafka topic

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

---

## 9. 替代方案考慮

| 方案 | 放棄原因 |
|---|---|
| 傳統 Spring Boot + PostgreSQL row lock | Hotspot 帳戶下 P95 無法達標 |
| Shard + Rebalancing | 資金管理複雜，rebalancing 引入資金缺口風險 |
| Pre-authorized Limit + 異步 Settlement | 不符合「同步原子落帳」要求 |
| Redis 作 balance cache | 一致性風險不可接受（金融場景） |
| LMAX Disruptor | 解決 thread messaging 延遲，不解決 DB round-trip，不適用 |

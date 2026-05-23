# Next-Gen Internal Ledger Platform
## 技術需求規格全文件

**版本**: v0.4  
**日期**: 2026-05-23  
**狀態**: Draft for Review  
**系統**: Next-Gen Internal Ledger Platform  
**定位**: iBank 核心帳務底座，支持多法人、多產品、多幣種、多賬本的雙分錄帳務處理

---

## 文件說明

本文件包含 Next-Gen Internal Ledger Platform 的完整技術需求規格，涵蓋架構決策、功能需求及非功能需求。所有需求基於以下核心原則：

- **Raft + CQRS + Account-Level Queue 架構**（ADR-001）
- **雙分錄，借貸永遠平衡**
- **Append-only 不可變帳務流水**
- **同步原子落帳，防止重複出金**
- **Hotspot 帳戶高性能（COMPANY_ACC RFQ 場景）**
- **可追溯、可對帳、可審計**

### 修訂記錄

| 版本 | 日期 | 修訂內容 | 修訂人 |
|---|---|---|---|
| v0.1 | 2026-05-16 | 初稿 | Ledger Platform Team |
| v0.2 | 2026-05-22 | ADR-001 2.2 節：新增 sofa-common-tools 版本相容性說明（解決 Spring Boot 3.4.4 + SOFAJRaft 1.3.15 的 logback 衝突） | Ledger Platform Team |
| v0.3 | 2026-05-23 | F-002/F-005/F-008：新增 `position` 欄位（CURRENT/LOCKED/FROZEN）支持餘額位置追蹤；AccountBalanceKey 擴展為 (accountId, balanceType, position, currency)；新增驗證規則 V-13 | Ledger Platform Team |
| v0.4 | 2026-05-23 | 新增 Core Concepts 章節：定義 Account、BalanceType、Position 三大核心概念與關聯功能 | Ledger Platform Team |

---

## 目錄

| 文件 | 章節 | 說明 |
|---|---|---|
| Core Concepts | 核心概念 | Account、BalanceType、Position 定義與關係 |
| ADR-001 | 架構決策 | Raft + CQRS + Account-Level Queue 選型理由 |
| F-001 | Balance Type Registry | Balance Type 配置化管理，不改代碼新增 type |
| F-002 | Posting API v2 | 核心入帳 API，支持 Multi-Account 原子 Posting |
| F-003 | Manual Adjustment | 人工調帳，強制 Maker-Checker 審批 |
| F-004 | Reversal | 反轉已過帳 Journal，Append-only 對沖 |
| F-005 | Balance Query v2 | 實時餘額查詢，讀 in-memory State Machine |
| F-006 | Journal Query | 帳務流水查詢，全鏈路追溯 |
| F-007 | Reconciliation | L1/L2/L3 三層對帳，差異追蹤 |
| F-008 | State Machine Design | Raft State Machine 核心設計 |
| F-009 | Accounting Period / EOD | 帳期管理與 EOD 關期流程 |
| F-010 | Account Management | 帳戶生命週期管理 |
| OPS-001 | SRE 運維指南 | RocksDB 壓實、Raft 復原、MySQL 同步修復 |
| NFR | 非功能需求 | 性能、可用性、一致性、安全、容量 |

---

## Core Concepts（核心概念）

本節定義 Ledger Platform 的三大核心概念：Account、BalanceType、Position。這些概念貫穿所有功能需求（F-001 至 F-011）。

### Account（帳戶）

Account 代表帳務實體，如客戶帳戶、公司帳戶、Nostro 帳戶、Suspense 帳戶。

**核心屬性：**
- `accountId`: 唯一識別碼（如 `CLIENT_ACC_001`, `COMPANY_FX_ACC`）
- `status`: 生命週期狀態（`ACTIVE` → `FROZEN` → `CLOSED`）
- `balanceTypes`: 支援的餘額類型列表（初始化時配置）
- `currencies`: 支援的幣種列表
- `metadata`: 客戶資訊、法人代碼、產品代碼等

**生命週期：**

```
CREATE → ACTIVE → FROZEN → CLOSED
          ↑         ↓
        UNFREEZE
```

- **CREATE**: 建立 Account，初始化 BalanceType（F-010）
- **ACTIVE**: 可接受 Posting、Query
- **FROZEN**: 暫停所有操作（AML 調查、法務凍結）
- **CLOSED**: 餘額為 0 才可關閉，不可再操作

**詳細規格見 F-010 Account Management。**

---

### BalanceType（餘額類型）

BalanceType 定義餘額的業務語義與約束規則。每個 Account 可有多個 BalanceType。

**核心屬性：**
- `typeCode`: 餘額類型代碼（如 `AVAILABLE_BALANCE`, `TRADEAHEAD_BALANCE`）
- `allowNegative`: 是否允許負餘額（overdraft）
- `zeroFloorEnforce`: 是否強制餘額 ≥ 0
- `overdrawnAlertThreshold`: 負餘額告警門檻（如 -500,000）
- `creditLimit`: 正餘額上限（如 1,000,000）

**預設 BalanceType：**

| typeCode | allowNegative | zeroFloorEnforce | overdrawnAlertThreshold | 用途 |
|---|---|---|---|---|
| AVAILABLE_BALANCE | false | true | N/A | 可用餘額（結算、提款） |
| TRADEAHEAD_BALANCE | true | false | -500,000 | 交易預留（RFQ 前置扣款） |
| LOCKED_BALANCE | false | false | N/A | Maker-Checker 待審餘額 |
| FROZEN_BALANCE | false | false | N/A | 法規凍結餘額 |

**約束規則：**

```
allowNegative=false:
  → Posting 時若 afterBalance < 0，拒絕（INSUFFICIENT_BALANCE）

allowNegative=true:
  → 允許負餘額至 overdrawnAlertThreshold
  → 超過門檻觸發 PagerDuty 告警

zeroFloorEnforce=true:
  → 強制 balance ≥ 0，即使是 allowNegative=true 也適用
```

**詳細規格見 F-001 Balance Type Registry。**

---

### Position（餘額位置）

Position 是 v0.4 新增欄位，用於區分同一 BalanceType 的不同子餘額狀態。

**Position 類型：**

| Position | 說明 | 使用場景 |
|---|---|---|
| CURRENT | 即時可用餘額 | 正常交易、結算、提款 |
| LOCKED | 鎖定待審餘額 | Maker-Checker 調帳草稿待批准 |
| FROZEN | 法規凍結餘額 | AML 調查、法院命令、監管凍結 |

**JournalLine 結構（v0.4）：**

```json
{
  "accountId": "CLIENT_ACC_001",
  "balanceType": "AVAILABLE_BALANCE",
  "position": "CURRENT",      // 新增欄位
  "currency": "USD",
  "entryType": "DEBIT",
  "amount": "1000.00"
}
```

**AccountBalanceKey 擴展：**

```
AccountBalanceKey = (accountId, balanceType, position, currency)
```

每個 Account 在每個 BalanceType + Position + Currency 組合下維護獨立餘額。

**餘額聚合查詢：**

```
GET /ledger/balances?accountId=X&balanceType=AVAILABLE&position=CURRENT
  → 返回 CURRENT 子餘額

GET /ledger/balances?accountId=X&aggregate=true
  → 返回所有 Position 總和（CURRENT + LOCKED + FROZEN）
```

**詳細規格見 F-002 Posting API v2 §3.4 Position Field、F-005 Balance Query v2。**

---

### Core Concepts 總結表

| 概念 | 定義 | 關聯功能 |
|---|---|---|
| Account | 帳務實體，生命週期 ACTIVE/FROZEN/CLOSED | F-010 Account Management |
| BalanceType | 餘額類型，定義 overdraft/creditLimit 規則 | F-001 Balance Type Registry |
| Position | 子餘額位置 CURRENT/LOCKED/FROZEN | F-002 Posting v2, F-005 Balance Query |

**三者關係：**

```
Account
  └─ balanceTypes: [AVAILABLE, TRADEAHEAD, LOCKED, FROZEN]
      └─ positions: [CURRENT, LOCKED, FROZEN]
          └─ currencies: [USD, HKD, EUR]
              └─ balance: 5000.00
```

每個餘額由四維鍵唯一定位：

```
(accountId, balanceType, position, currency) → balance
```

---

---

## 系統架構總覽

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              客戶端層                                         │
│          HTTP/gRPC  →  Posting / Reversal / Adjustment / Query               │
└───────────────────────────────────┬─────────────────────────────────────────┘
                                    │
                    ┌───────────────┴───────────────┐
                    ▼                               ▼
         ┌────────────────────┐          ┌────────────────────┐
         │     寫入請求        │          │     讀取請求        │
         │  (Posting/Rev/Adj) │          │ (Balance / Journal)│
         └─────────┬──────────┘          └─────────┬──────────┘
                   │                               │
                   ▼                               ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                        帳務寫域（Raft 集群）                                   │
│                                                                              │
│   ┌─────────────────────────────┐   ┌───────────────────────────────────┐   │
│   │   Ledger RESTful 控制器      │   │        SOFAJRaft 集群              │   │
│   │  ┌─────────────────────┐    │   │  ┌────────┐    ┌─────────────┐   │   │
│   │  │ AccountQueueManager │    │   │  │ Leader │◄──►│  Follower   │   │   │
│   │  │(LinkedBlockingQueue │    │   │  │        │    │             │   │   │
│   │  │ per account + VT)   │    │   │  │ RocksDB│    │  RocksDB    │   │   │
│   │  └──────────┬──────────┘    │   │  └───┬────┘    └──────┬──────┘   │   │
│   │             │               │   │      │                │          │   │
│   │             ▼               │   │      │  Raft Log      │          │   │
│   │  ┌─────────────────────┐    │   │      ▼                ▼          │   │
│   │  │ LedgerRaftStateMach │◄───┼───┼────►┌─────────────────────────┐  │   │
│   │  │ · onApply()         │    │   │     │      Learner (non-voting)│  │   │
│   │  │ · snapshotSave/Load │    │   │     │  Async sync → Kafka      │  │   │
│   │  └──────────┬──────────┘    │   │     └─────────────────────────┘  │   │
│   │             │               │   └───────────────────────────────────┘   │
│   │             ▼               │                                           │
│   │  ┌─────────────────────┐    │                                           │
│   │  │  LedgerStateMachine │    │                                           │
│   │  │  · applyPosting()   │    │                                           │
│   │  │  · applyReversal()  │    │                                           │
│   │  └──────────┬──────────┘    │                                           │
│   │             │               │                                           │
│   │    ┌────────┼────────┐      │                                           │
│   │    ▼        ▼        ▼      │                                           │
│   │ ┌──────┐ ┌──────┐ ┌────────┐│                                           │
│   │ │餘額存│ │流水存│ │冪等存  ││                                           │
│   │ └──┬───┘ └──┬───┘ └───┬────┘│                                           │
│   │    │        │         │     │                                           │
│   │    ▼        ▼         │     │                                           │
│   │ ┌────────┐ ┌────────┐ │     │                                           │
│   │ │RocksDB │ │ Kafka  │◄┘     │                                           │
│   │ │(WAL)   │ │ 生產者  │       │                                           │
│   │ └────────┘ └────────┘       │                                           │
│   └─────────────────────────────┘                                           │
│                                                                              │
└───────────────────────────────────┬─────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                        投影 / 讀域                                            │
│                                                                              │
│   ┌─────────────────────────────┐   ┌───────────────────────────────────┐   │
│   │   ProjectionConsumer        │   │   ProjectionQueryController       │   │
│   │   (Kafka Listener)          │   │   · /query/journal                │   │
│   │   · onBalanceChange()       │   │   · /query/balance                │   │
│   │   · onAccountCreated()      │   └───────────────────────────────────┘   │
│   └─────────────┬───────────────┘                                           │
│                 │                                                            │
│                 ▼                                                            │
│   ┌─────────────────────────────────────────────────────────────────────┐   │
│   │              ShardingSphere-JDBC  →  MySQL View Layer                │   │
│   │                                                                      │   │
│   │   ┌──────────┐  ┌──────────────┐  ┌──────────────────────────────┐   │   │
│   │   │  journal │  │account_balance│  │ journal_line_0 .. _3 (按       │   │   │
│   │   │ (單表)   │  │   (單表)      │  │   account_id 哈希分片)        │   │   │
│   │   └──────────┘  └──────────────┘  └──────────────────────────────┘   │   │
│   └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│   查詢路徑：                                                                  │
│   ┌─────────────────┐      ┌─────────────────────────────────────────────┐   │
│   │ 餘額查詢（即時） │─────→│  讀取 Leader 記憶體 State Machine            │   │
│   │                 │      │  強一致性 · P95 ≤ 2ms                        │   │
│   └─────────────────┘      └─────────────────────────────────────────────┘   │
│                                                                              │
│   ┌─────────────────┐      ┌─────────────────────────────────────────────┐   │
│   │ 流水查詢（最終） │─────→│  讀取 MySQL View Layer                      │   │
│   │                 │      │  最終一致性 · 延遲 ≤ 1s                      │   │
│   └─────────────────┘      └─────────────────────────────────────────────┘   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

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

**相依版本管理**：
- SOFAJRaft 1.3.15 依賴 sofa-common-tools 進行日誌初始化
- sofa-common-tools 1.0.12（SOFAJRaft 預設）與 Spring Boot 3.4.4 的 logback 1.5.x 不相容
- **解決方案**：於 `pom.xml` 的 `dependencyManagement` 中覆寫 sofa-common-tools 至 2.1.1+
- sofa-common-tools 2.1.1 支援 logback 1.5.x，移除對 `ContextInitializer.configureByResource(URL)` 的呼叫

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


---

# F-001 Balance Type Registry — 功能需求規格

**文件版本**: v0.2  
**功能**: F-001 Balance Type Registry  
**系統**: Next-Gen Internal Ledger Platform  
**定位說明**: 本 Ledger 為純 Booking Engine，定位為高頻、高並發帳務處理核心。配置管理、Limit 管控、Maker-Checker、客戶級別 Override 均屬上游 Domain 的責任，本系統不負責。  
**狀態**: Draft for Review

---

## 1. 功能概述

本功能定義「Balance Type Registry」機制，允許 Ledger Platform 以純配置方式新增、修改或停用各類 Balance Type，**無需改動任何 Source Code，無需重新部署**。

每種 Balance Type 擁有一套獨立的行為屬性，例如：是否允許負值、符號方向、計算來源規則、可見性範圍、過帳類型映射等。系統在執行 Balance 計算、校驗時，統一從 Registry 讀取配置，以策略模式動態應用對應規則。

### 系統定位邊界

| 責任 | 本 Ledger 負責 | 上游 Domain 負責 |
|---|---|---|
| Balance 計算與落帳 | ✅ | ❌ |
| Balance Type 配置讀取與執行 | ✅ | ❌ |
| Balance Type 配置的新增/修改 | ✅（提供 API） | ✅（調用方） |
| Maker-Checker 審批流程 | ❌ | ✅ |
| 客戶/帳戶級別 Limit 管控 | ❌ | ✅ |
| 帳戶開立、客戶 KYC | ❌ | ✅ |

> **設計原則**：Ledger 只認識「配置說什麼，就執行什麼」。業務規則的正確性由配置方（上游 Domain）負責，Ledger 負責高效、正確地執行。

### 設計目標

- **可擴展性**：新增 Balance Type 只需調用 Registry API 新增一條配置記錄，無需 code change / release。
- **高性能**：Registry 配置在應用層全量緩存，Balance 計算路徑零 DB 查詢，支持 10 萬並發量級。
- **可追溯性**：每條配置有完整版本歷史；Balance 計算結果附帶 `configVersion`，可追溯計算規則版本。
- **熱更新**：配置變更 5 秒內全節點生效，無需重啟。

---

## 2. 核心概念

### 2.1 Balance Type 是什麼

Balance Type 是賬戶在某一業務視角下的餘額視圖，例如：

| Balance Type | 業務含義 | 典型場景 |
|---|---|---|
| `AVAILABLE_BALANCE` | 客戶當前可動用金額 | 出金校驗、授信判斷 |
| `CURRENT_BALANCE` | 賬戶簿記實際餘額 | 會計核對、日終結算 |
| `PENDING_BALANCE` | 未最終結算但已扣留金額 | 交易中、T+N 結算 |
| `HOLD_BALANCE` | 被凍結/法律扣押的金額 | 合規凍結、抵押品 |
| `BROKERAGE_BALANCE` | 證券相關可用金額 | 券商業務 |
| `TRADE_AHEAD_BALANCE` | 交易前置佔用（負帳） | 做空、預授信額度 |
| `COLLATERAL_BALANCE` | 抵押品計算餘額 | Margin Lending |
| `SHADOW_BALANCE` | 影子帳/管理帳視圖 | 內部報表、監管申報 |

以上僅為示例，系統**不硬編碼任何 type 名稱或邏輯**；所有 type 均從 Registry 讀取配置動態執行。

---

## 3. Balance Type 配置屬性（Registry Schema）

### 3.1 基礎身份屬性

| 屬性名 | 類型 | 必填 | 說明 |
|---|---|---|---|
| `typeCode` | `string` | ✅ | 全局唯一，大寫蛇形，例如 `TRADE_AHEAD_BALANCE`。一旦創建，不可修改。 |
| `displayName` | `i18n map` | ✅ | 多語言顯示名稱，例如 `{"en": "Trade Ahead Balance", "zh-HK": "交易前置餘額"}` |
| `description` | `string` | ✅ | 業務說明，用於文檔、審計報告 |
| `category` | `enum` | ✅ | `ACTUAL` / `PROJECTED` / `RESERVED` / `SHADOW` |
| `status` | `enum` | ✅ | `ACTIVE` / `INACTIVE` / `DEPRECATED` |
| `effectiveFrom` | `datetime` | ✅ | 生效日期 |
| `effectiveTo` | `datetime` | ❌ | 失效日期，null 代表長期有效 |

### 3.2 符號與方向屬性（核心）

| 屬性名 | 類型 | 必填 | 說明 |
|---|---|---|---|
| `signConvention` | `enum` | ✅ | `NORMAL_CREDIT`：貸方增加餘額（標準） / `NORMAL_DEBIT`：借方增加餘額（反向） |
| `allowNegative` | `boolean` | ✅ | 是否允許 Balance 為負數 |
| `negativeSemantics` | `enum` | 條件必填 | 當 `allowNegative=true` 時必填，負數的業務含義：`OVERDRAFT` / `SHORT_POSITION` / `PRE_AUTHORIZED` / `CREDIT_UTILIZATION` |
| `zeroFloorEnforce` | `boolean` | ✅ | 是否強制下限為 0（`allowNegative=true` 時此項自動為 false） |
| `overdrawnAlertThreshold` | `decimal` | ❌ | 觸發超支預警閾值（負數），例如 `-500000`，僅 `allowNegative=true` 時有效 |

**符號語義約束（適用於所有 Posting 路徑，包括 Manual Adjustment）：**

```
IF allowNegative=false:
  任何 Posting / Adjustment 不得使 Balance 結果 < 0
  違反時：拒絕請求，返回 BALANCE_FLOOR_BREACH

IF allowNegative=true:
  任何 Posting / Adjustment 不得使 Balance 結果 > 0
  （此類 Balance 業務定義上永遠為負或零，正數違反業務語義）
  違反時：拒絕請求，返回 BALANCE_CEILING_BREACH
```

> 這兩條規則是系統的**硬性不可繞過校驗**，無例外，無強制覆蓋。

**TRADE_AHEAD_BALANCE 示例說明：**

```
signConvention     = NORMAL_DEBIT     → 借方記帳 = 餘額增加（前置佔用增加）
allowNegative      = true             → 正常狀態，餘額為負不代表異常
negativeSemantics  = PRE_AUTHORIZED   → 負數 = 有效預授信佔用
zeroFloorEnforce   = false            → 不設下限 0
overdrawnAlertThreshold = -500000     → 超過此值才觸發告警
```

### 3.3 計算來源規則（Composition Rules）

| 屬性名 | 類型 | 必填 | 說明 |
|---|---|---|---|
| `compositionLogic` | `enum` | ✅ | `SUM`：對符合條件的流水求和 / `FORMULA`：基於其他 Balance Type 做公式計算 |
| `compositionRules` | `list<CompositionRule>` | 條件必填 | 當 `compositionLogic=SUM` 時必填 |
| `formula` | `string` | 條件必填 | 當 `compositionLogic=FORMULA` 時必填，只允許引用已定義的 `typeCode`，例如 `CURRENT_BALANCE - HOLD_BALANCE - PENDING_BALANCE` |

**CompositionRule 結構：**

| 屬性名 | 類型 | 說明 |
|---|---|---|
| `includedPostingTypes` | `list<string>` | 納入計算的 posting type |
| `excludedPostingTypes` | `list<string>` | 明確排除的 posting type |
| `includedEntryStates` | `list<enum>` | 納入的 journal entry 狀態：`CONFIRMED` / `PENDING` / `PROVISIONAL` |
| `sign` | `enum` | 此規則貢獻方向：`ADD` / `SUBTRACT` |

> **Formula 安全限制**：formula 只支持對已存在的 `typeCode` 做四則運算引用，不支持任意表達式。循環引用在配置寫入時校驗並拒絕。

> **實現狀態**：目前程式碼僅支援 `INDEPENDENT`（獨立直接記帳）的 balance type。`FORMULA` 組合邏輯（例如 `CURRENT_BALANCE - HOLD_BALANCE - PENDING_BALANCE`）在此文件記載為設計目標，但**尚未實作**。所有 balance type 目前皆視為可直接記帳的獨立桶子。

### 3.4 貨幣屬性

| 屬性名 | 類型 | 必填 | 說明 |
|---|---|---|---|
| `currencyScope` | `enum` | ✅ | `SINGLE_CCY` / `MULTI_CCY` / `BASE_CCY_ONLY` |
| `fxRevaluationEnabled` | `boolean` | ✅ | 是否支持 FX Revaluation |
| `fxRevaluationRateSource` | `enum` | ❌ | `MID_RATE` / `BID_RATE` / `ASK_RATE` / `CLOSING_RATE` |

### 3.5 可見性與存取控制

| 屬性名 | 類型 | 必填 | 說明 |
|---|---|---|---|
| `visibilityScope` | `list<enum>` | ✅ | `INTERNAL_ONLY` / `PRODUCT_API` / `CLIENT_FACING` / `REGULATORY` |
| `queryableByClient` | `boolean` | ✅ | 是否可透過客戶查詢 API 返回 |
| `requiredPermissions` | `list<string>` | ❌ | 讀取此 Balance 所需的 permission code |

### 3.6 告警與監控屬性

| 屬性名 | 類型 | 必填 | 說明 |
|---|---|---|---|
| `monitoringEnabled` | `boolean` | ✅ | 是否啟用餘額監控 |
| `alertRules` | `list<AlertRule>` | ❌ | 告警規則列表 |

**AlertRule 結構：**

| 屬性名 | 類型 | 說明 |
|---|---|---|
| `condition` | `enum` | `BELOW_THRESHOLD` / `ABOVE_THRESHOLD` / `EQUALS_ZERO` / `NEGATIVE` |
| `threshold` | `decimal` | 閾值金額 |
| `severity` | `enum` | `INFO` / `WARNING` / `CRITICAL` |
| `notificationChannel` | `list<string>` | `["EMAIL", "PAGERDUTY", "SLACK"]` |

### 3.7 快照與快取屬性

| 屬性名 | 類型 | 必填 | 說明 |
|---|---|---|---|
| `snapshotEnabled` | `boolean` | ✅ | 是否啟用定時快照 |
| `snapshotFrequency` | `enum` | ❌ | `EOD` / `INTRADAY_HOURLY` / `ON_CHANGE` |
| `cacheEnabled` | `boolean` | ✅ | 是否允許 in-memory 緩存此 Balance |
| `cacheTtlSeconds` | `integer` | ❌ | 緩存存活時間（秒），0 代表不緩存 |

### 3.8 版本控制屬性

| 屬性名 | 類型 | 說明 |
|---|---|---|
| `configVersion` | `integer` | 每次修改自動遞增，從 1 開始 |
| `createdBy` | `string` | 創建者 operator ID（由上游 Domain 傳入） |
| `createdAt` | `datetime` | 創建時間 |
| `lastModifiedBy` | `string` | 最後修改者 |
| `lastModifiedAt` | `datetime` | 最後修改時間 |
| `changeReason` | `string` | 每次修改必須填寫變更原因 |

---

## 4. 完整配置示例

### 4.1 AVAILABLE_BALANCE（公式計算型，不允許負數）

```json
{
  "typeCode": "AVAILABLE_BALANCE",
  "displayName": {"en": "Available Balance", "zh-HK": "可用餘額"},
  "category": "PROJECTED",
  "status": "ACTIVE",
  "signConvention": "NORMAL_CREDIT",
  "allowNegative": false,
  "zeroFloorEnforce": true,
  "compositionLogic": "FORMULA",
  "formula": "CURRENT_BALANCE - HOLD_BALANCE - PENDING_BALANCE",
  "currencyScope": "SINGLE_CCY",
  "fxRevaluationEnabled": false,
  "visibilityScope": ["PRODUCT_API", "CLIENT_FACING"],
  "queryableByClient": true,
  "snapshotEnabled": false,
  "cacheEnabled": true,
  "cacheTtlSeconds": 5,
  "monitoringEnabled": true,
  "alertRules": [
    {"condition": "BELOW_THRESHOLD", "threshold": 0, "severity": "WARNING", "notificationChannel": ["SLACK"]}
  ]
}
```

### 4.2 TRADE_AHEAD_BALANCE（負帳型，永遠為負或零）

```json
{
  "typeCode": "TRADE_AHEAD_BALANCE",
  "displayName": {"en": "Trade Ahead Balance", "zh-HK": "交易前置餘額"},
  "category": "RESERVED",
  "status": "ACTIVE",
  "signConvention": "NORMAL_DEBIT",
  "allowNegative": true,
  "negativeSemantics": "PRE_AUTHORIZED",
  "zeroFloorEnforce": false,
  "overdrawnAlertThreshold": -500000.00,
  "compositionLogic": "SUM",
  "compositionRules": [
    {
      "includedPostingTypes": ["TRADE_COMMITMENT", "TRADE_EXECUTION"],
      "includedEntryStates": ["CONFIRMED", "PENDING"],
      "sign": "SUBTRACT"
    }
  ],
  "currencyScope": "SINGLE_CCY",
  "fxRevaluationEnabled": false,
  "visibilityScope": ["INTERNAL_ONLY", "PRODUCT_API"],
  "queryableByClient": false,
  "requiredPermissions": ["TRADE_BALANCE_READ"],
  "snapshotEnabled": true,
  "snapshotFrequency": "EOD",
  "cacheEnabled": true,
  "cacheTtlSeconds": 2,
  "monitoringEnabled": true,
  "alertRules": [
    {"condition": "BELOW_THRESHOLD", "threshold": -500000.00, "severity": "CRITICAL", "notificationChannel": ["PAGERDUTY", "EMAIL"]}
  ]
}
```

### 4.3 HOLD_BALANCE（凍結型，不允許負數）

```json
{
  "typeCode": "HOLD_BALANCE",
  "displayName": {"en": "Hold Balance", "zh-HK": "凍結餘額"},
  "category": "RESERVED",
  "status": "ACTIVE",
  "signConvention": "NORMAL_CREDIT",
  "allowNegative": false,
  "zeroFloorEnforce": true,
  "compositionLogic": "SUM",
  "compositionRules": [
    {
      "includedPostingTypes": ["COMPLIANCE_FREEZE", "LEGAL_HOLD", "COLLATERAL_PLEDGE"],
      "includedEntryStates": ["CONFIRMED"],
      "sign": "ADD"
    }
  ],
  "currencyScope": "SINGLE_CCY",
  "fxRevaluationEnabled": false,
  "visibilityScope": ["INTERNAL_ONLY", "REGULATORY"],
  "queryableByClient": false,
  "requiredPermissions": ["COMPLIANCE_BALANCE_READ"],
  "snapshotEnabled": true,
  "snapshotFrequency": "EOD",
  "cacheEnabled": true,
  "cacheTtlSeconds": 30,
  "monitoringEnabled": false
}
```

---

## 5. 高頻性能設計（10 萬並發）

### 5.1 三層緩存架構

```
請求路徑（Balance 計算時讀取 Registry）:

L1: Process-level in-memory cache（每個服務實例本地）
    → 命中率目標：> 99.9%
    → 更新方式：訂閱 config_updated 事件後主動刷新
    → 數據結構：HashMap<typeCode, BalanceTypeConfig>，讀無鎖

L2: Distributed cache（Redis Cluster）
    → 作為 L1 miss 時的回源
    → Key: ledger:registry:balance_type:{typeCode}
    → TTL: 60s（被動過期兜底）

L3: DB（PostgreSQL / Aurora）
    → 作為 source of truth
    → 僅在 L1/L2 均 miss 時查詢（正常路徑不觸達）
```

### 5.2 配置熱更新流程

```
1. 上游 Domain 調用 PUT /admin/ledger/balance-types/{typeCode}
2. Ledger Registry Service 校驗配置合法性（循環引用、schema 校驗）
3. 寫入 DB + 歷史快照
4. 發布 config_updated 事件至 Message Bus
5. 所有 Ledger Engine 節點訂閱，5 秒內完成 L1 刷新
SLA: 配置變更從寫入到全節點生效 ≤ 5 秒
```

---

## 6. 數據模型

### 6.1 balance_type_registry 表（MySQL 8.0）

所有 View Layer 表統一採用以下審計欄位設計：
- `id BIGINT AUTO_INCREMENT PRIMARY KEY`：代理主鍵，供內部關聯與分片使用
- `created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)`：創建時間
- `updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)`：最後更新時間
- 業務鍵（如 `type_code`、`account_id`、`journal_id`）設為 `NOT NULL UNIQUE`，不作為主鍵

```sql
CREATE TABLE balance_type_registry (
  id                           BIGINT AUTO_INCREMENT PRIMARY KEY,
  type_code                    VARCHAR(64)   NOT NULL UNIQUE,
  display_name                 JSON          NOT NULL,
  description                  TEXT          NOT NULL,
  category                     VARCHAR(32)   NOT NULL,
  status                       VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
  sign_convention              VARCHAR(32)   NOT NULL,
  allow_negative               BOOLEAN       NOT NULL DEFAULT FALSE,
  negative_semantics           VARCHAR(32),
  zero_floor_enforce           BOOLEAN       NOT NULL DEFAULT TRUE,
  currency_scope               VARCHAR(32)   NOT NULL,
  config_version               INTEGER       NOT NULL DEFAULT 1,
  created_by                   VARCHAR(64)   NOT NULL,
  created_at                   TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at                   TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  last_modified_by             VARCHAR(64),
  last_modified_at             TIMESTAMP(6),
  change_reason                TEXT          NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

> **完整 View Layer Schema**：參見專案根目錄 `init.sql`，內含 `journal`、`journal_line`、`account`、`account_balance` 等全部建表語句。
>
> **account_balance 表結構（v0.3 更新）**：
> - 新增 `position VARCHAR(16) NOT NULL` 欄位（值為 CURRENT / LOCKED / FROZEN）
> - UNIQUE KEY 從 `(account_id, balance_type, currency)` 更新為 `(account_id, balance_type, position, currency)`
> - `frozen_amount` / `locked_amount` 保留為 legacy 欄位（可用於報表兼容），但主要餘額追蹤改用 `position` 機制
> - 冪等 UPSERT 以 `(account_id, balance_type, position, currency)` 為 UNIQUE KEY

---

## 7. API 設計

```
POST   /admin/ledger/balance-types                        -- 新增
PUT    /admin/ledger/balance-types/{typeCode}             -- 修改（生成新 configVersion）
PATCH  /admin/ledger/balance-types/{typeCode}/status      -- 啟用 / 停用
GET    /admin/ledger/balance-types                        -- 查詢所有
GET    /admin/ledger/balance-types/{typeCode}             -- 查詢單個
GET    /admin/ledger/balance-types/{typeCode}/history     -- 查詢配置歷史
```

所有寫操作必須包含 `changeReason`，否則返回 `400 CHANGE_REASON_REQUIRED`。

---

## 8. 驗收標準

| # | 驗收條件 | 測試方式 |
|---|---|---|
| AC-01 | 新增 Balance Type 只需調用 POST API，無需 code change 或重啟 | 功能測試 |
| AC-02 | 配置變更後，5 秒內所有節點 L1 cache 完成刷新 | 熱更新測試 |
| AC-03 | `allowNegative=false` 的 Balance，任何 Posting / Adjustment 不得使結果 < 0 | 單元測試 |
| AC-04 | `allowNegative=true` 的 Balance，任何 Posting / Adjustment 不得使結果 > 0 | 單元測試 |
| AC-05 | `TRADE_AHEAD_BALANCE` 餘額低於 `overdrawnAlertThreshold` 時，發出告警事件但不拒絕過帳 | 集成測試 |
| AC-06 | 每次修改配置，`balance_type_config_history` 有完整快照記錄 | 審計測試 |
| AC-07 | Balance 查詢 Response 包含 `configVersion` | API 測試 |
| AC-08 | 停用的 Balance Type 不參與計算，查詢返回空 | 功能測試 |
| AC-09 | 未填 `changeReason` 的修改請求被拒絕 | 校驗測試 |
| AC-10 | 10 萬並發壓測下，Registry 讀取全走 L1 cache，DB 查詢數量為 0 | 性能測試 |
| AC-11 | Formula 配置中出現循環引用，寫入時即時校驗拒絕 | 邊界測試 |


---

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
| `position` | `enum` | ✅ | 餘額位置：`CURRENT` / `LOCKED` / `FROZEN`（見 position 說明） |
| `currency` | `string` | ✅ | ISO 4217 幣種代碼 |
| `entryType` | `enum` | ✅ | `DEBIT` / `CREDIT` |
| `amount` | `decimal` | ✅ | 必須 > 0 |
| `description` | `string` | ❌ | 分錄描述 |

**Position 欄位說明**

Position 用於區分同一帳戶同一 Balance Type 下不同用途的餘額桶：

| Position | 說明 | 典型場景 |
|---|---|---|
| `CURRENT` | 正常可用餘額位置 | 一般交易入帳 |
| `LOCKED` | 鎖定餘額（如交易進行中） | RFQ 成交前鎖定、待確認交易 |
| `FROZEN` | 凍結餘額（合規、法律扣押） | 合規凍結、抵押品扣押 |

同一 (accountId, balanceType, currency) 下可有多個 position 的餘額，各 position 獨立計算、獨立校驗。

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
          "position": "CURRENT",
          "currency": "USD",
          "entryType": "DEBIT",
          "amount": 800000.00,
          "description": "RFQ Client USD sell"
        },
        {
          "accountId": "COMPANY_FX_ACC",
          "balanceType": "AVAILABLE_BALANCE",
          "position": "CURRENT",
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
          "position": "CURRENT",
          "currency": "HKD",
          "entryType": "DEBIT",
          "amount": 6240000.00,
          "description": "RFQ Company HKD pay"
        },
        {
          "accountId": "CLIENT_ACC_001",
          "balanceType": "AVAILABLE_BALANCE",
          "position": "CURRENT",
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
| V-09 | 每個帳戶的對應 balanceType + position + currency 必須已初始化 | `BALANCE_NOT_INITIALIZED` |
| V-10 | `allowNegative=false` 的 balance，DEBIT 後不得低於 0 | `INSUFFICIENT_BALANCE` |
| V-11 | `allowNegative=true` 的 balance（如 TRADE_AHEAD_BALANCE），CREDIT 後不得高於 0 | `CREDIT_EXCEEDS_LIMIT` |
| V-12 | 帳戶未被凍結（`account.status = ACTIVE`） | `ACCOUNT_FROZEN` |
| V-13 | `LOCKED` / `FROZEN` position 的餘額不得為負（即使 `allowNegative=true`） | `POSITION_BALANCE_FLOOR_BREACH` |

**V-13 詳細說明**：

```
LOCKED / FROZEN position 是受限餘額，業務語義上不允許透支。
即使 Balance Type 配置 allowNegative=true（如 TRADE_AHEAD_BALANCE），
LOCKED / FROZEN position 仍受以下約束：

IF position IN ('LOCKED', 'FROZEN'):
  DEBIT 後餘額不得 < 0 → 拒絕，返回 POSITION_BALANCE_FLOOR_BREACH

只有 CURRENT position 可以跟隨 Balance Type 的 allowNegative 設定。
```

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
          "position": "CURRENT",
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
          "position": "CURRENT",
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


---

# F-003 Manual Adjustment — 功能需求規格

**文件版本**: v0.1  
**功能**: F-003 Manual Adjustment（人工調帳）  
**系統**: Next-Gen Internal Ledger Platform  
**狀態**: Draft for Review  
**依賴**: ADR-001、F-002 Posting API、F-008 State Machine Design

---

## 1. 功能概述

Manual Adjustment 是由操作員直接發起的單邊或雙邊帳務調整，不依附於任何業務事件（如交易、費用）。它用於處理以下場景：系統對帳差異補帳、利息手動入帳、費用豁免、系統遷移數據修正。

**與 Posting 的區別**：
- Posting 由業務系統發起，有明確的業務事件 ID
- Manual Adjustment 由人工操作員發起，必須有審批記錄
- Manual Adjustment 的 `journalType = MANUAL_ADJUSTMENT`，在報表和對帳中單獨統計

---

## 2. 適用場景

| 場景 | 說明 |
|---|---|
| 對帳差異補帳 | 外部清算返回差額，需手動補入 |
| 利息手動入帳 | 計息系統故障，需人工補入利息 |
| 費用豁免 | 已入帳費用需人工豁免（Reversal + 豁免入帳） |
| 系統遷移 | 舊系統餘額搬到新 Ledger 的初始入帳 |
| 錯誤修正 | 無法通過 Reversal 解決的複雜場景 |

---

## 3. Maker-Checker 強制要求

**所有 Manual Adjustment 必須經過 Maker-Checker 雙重審批**，這是 ibank 合規要求，不可繞過：

```
Maker（製作者）:
  提交 Adjustment Draft（草稿狀態）
  → 系統做前置校驗，但不執行
  → 返回 draftId

Checker（審核者）:
  審閱 Draft 內容
  → 批准（Approve）→ 系統執行 Adjustment
  → 拒絕（Reject）→ Draft 作廢

約束：
  - Maker 和 Checker 不能是同一人
  - Draft 有效期：24 小時（可配置）
  - 超時未審批：自動作廢
  - Checker 批准後不可撤回（需用 Reversal）
```

---

## 4. API 設計

### 4.1 Step 1：Maker 提交草稿

```
POST /ledger/adjustments/draft
```

**Request Body**

| 欄位 | 類型 | 必填 | 說明 |
|---|---|---|---|
| `draftRequestId` | `string` | ✅ | 冪等鍵 |
| `adjustmentType` | `enum` | ✅ | 調整類型，見下表 |
| `adjustmentReason` | `string` | ✅ | 自由文字說明（最長 1000 字） |
| `valueDate` | `date` | ✅ | 帳務生效日 |
| `makerId` | `string` | ✅ | Maker 操作員 ID |
| `legs` | `list<Leg>` | ✅ | 同 F-002 Posting 格式 |
| `supportingRef` | `string` | ❌ | 支撐文件編號（如對帳報告 ID） |
| `metadata` | `map` | ❌ | 擴展欄位 |

**adjustmentType 枚舉**

| Code | 說明 |
|---|---|
| `RECONCILIATION_ADJUSTMENT` | 對帳差異補帳 |
| `INTEREST_ADJUSTMENT` | 利息手動調整 |
| `FEE_WAIVER` | 費用豁免 |
| `MIGRATION_ENTRY` | 系統遷移入帳 |
| `ERROR_CORRECTION` | 錯誤修正 |
| `REGULATORY_ADJUSTMENT` | 監管要求調整 |

**Response（草稿創建成功）**

```json
{
  "draftRequestId": "draft-req-abc123",
  "draftId": "ADJ-DRAFT-20260516-000001",
  "status": "PENDING_APPROVAL",
  "expiresAt": "2026-05-17T14:30:00.000Z",
  "makerId": "ops-user-001"
}
```

### 4.2 Step 2：Checker 審批

```
POST /ledger/adjustments/{draftId}/approve
POST /ledger/adjustments/{draftId}/reject
```

**Approve Request Body**

| 欄位 | 類型 | 必填 | 說明 |
|---|---|---|---|
| `requestId` | `string` | ✅ | 冪等鍵（防止重複審批） |
| `checkerId` | `string` | ✅ | Checker 操作員 ID（不能等於 makerId） |
| `checkerNote` | `string` | ❌ | 審批備注 |

**Reject Request Body**

| 欄位 | 類型 | 必填 | 說明 |
|---|---|---|---|
| `requestId` | `string` | ✅ | 冪等鍵 |
| `checkerId` | `string` | ✅ | Checker 操作員 ID |
| `rejectReason` | `string` | ✅ | 拒絕原因 |

---

## 5. 校驗規則

### 5.1 Draft 創建時校驗（Maker 提交）

| # | 規則 | 錯誤碼 |
|---|---|---|
| V-01 | legs 格式合法，借貸平衡 | `JOURNAL_UNBALANCED` |
| V-02 | 所有 accountId 存在且狀態為 ACTIVE | `ACCOUNT_NOT_FOUND` |
| V-03 | 所有 balanceType 在 Registry 中存在 | `BALANCE_TYPE_NOT_FOUND` |
| V-04 | `adjustmentType` 合法 | `INVALID_ADJUSTMENT_TYPE` |

**注意：Draft 創建時不做餘額校驗**，餘額校驗在 Checker Approve 並執行時才做。

### 5.2 Checker Approve 時校驗

| # | 規則 | 錯誤碼 |
|---|---|---|
| V-05 | `checkerId ≠ makerId` | `MAKER_CHECKER_SAME_PERSON` |
| V-06 | Draft 未過期（在 expiresAt 之前） | `DRAFT_EXPIRED` |
| V-07 | Draft status = `PENDING_APPROVAL` | `DRAFT_NOT_PENDING` |
| V-08 | 餘額校驗（同 F-002 V-08 ~ V-12） | 見 F-002 |

---

## 6. 執行流程

### 6.1 Maker 提交草稿（不走 Raft）

```
Draft 只做校驗和儲存，不入帳
→ 寫 MySQL adjustments_draft 表
→ 返回 draftId
→ 不提交 RaftCommand，不更新 State Machine
```

### 6.2 Checker Approve → 執行 Adjustment（走 Raft）

```
1. [Network Layer]
   校驗 checkerId ≠ makerId，Draft 未過期

2. [Ledger Layer]
   從 MySQL 載入 Draft 的 legs 內容
   → 按 accountId 升序路由到 Account Queue

3. [Account Queue Coordinator]
   冪等檢查（approve requestId）
   餘額校驗（V-08，讀 in-memory State Machine）
   構建 ADJUSTMENT_CMD

4. [Raft Layer]
   提交 ADJUSTMENT_CMD → Quorum commit

5. [State Machine Apply]
   生成 Journal（journalType = MANUAL_ADJUSTMENT）
   生成 JournalLine
   更新 in-memory balance
   寫 RocksDB WriteBatch
   更新 Draft status = EXECUTED（通過 Learner 同步到 MySQL）

6. [Response]
   返回 adjustmentJournalId
```

---

## 7. Draft 狀態機

```
        Maker 提交
             │
             ▼
    [PENDING_APPROVAL]
        │         │
    Checker    Checker
    Approve    Reject      Draft 過期（24h）
        │         │              │
        ▼         ▼              ▼
   [APPROVED] [REJECTED]    [EXPIRED]
        │
        ▼
   [EXECUTED]（入帳完成）
        │
        ▼
   [REVERSED]（如後續被 Reversal）
```

---

## 8. 審計要求

每筆 Manual Adjustment 在 MySQL 保存完整審計鏈路：

| 欄位 | 說明 |
|---|---|
| `draftId` | 草稿 ID |
| `adjustmentJournalId` | 最終入帳的 Journal ID |
| `makerId` + `makeTime` | 誰在何時提交草稿 |
| `checkerId` + `checkTime` | 誰在何時批准 |
| `checkerNote` | 審批備注 |
| `adjustmentType` | 調整類型 |
| `adjustmentReason` | 原因說明 |
| `supportingRef` | 支撐文件 |

---

## 9. 性能目標

| 操作 | 目標 |
|---|---|
| Draft 創建 P95 | ≤ 100ms（只寫 MySQL，不走 Raft） |
| Checker Approve P95 | ≤ 10ms（走 Raft，略高於 Posting） |

---

## 10. 驗收標準

| # | 驗收條件 | 測試方式 |
|---|---|---|
| AC-01 | Maker 提交 Draft，系統返回 draftId，未入帳 | 功能測試 |
| AC-02 | Checker 批准後，帳務正確入帳，Journal 類型為 MANUAL_ADJUSTMENT | 功能測試 |
| AC-03 | Checker 和 Maker 為同一人，返回 `MAKER_CHECKER_SAME_PERSON` | 功能測試 |
| AC-04 | 24 小時後未審批的 Draft，狀態自動變為 EXPIRED | 功能測試 |
| AC-05 | 對 EXPIRED / REJECTED / EXECUTED 的 Draft 執行 Approve，返回 `DRAFT_NOT_PENDING` | 功能測試 |
| AC-06 | 審批記錄（makerId、checkerId、時間）完整保存在 MySQL | 審計測試 |
| AC-07 | Approve 操作冪等，相同 requestId 重試不重複入帳 | 冪等測試 |
| AC-08 | Manual Adjustment Journal 在報表中獨立統計，與業務 Posting 分開 | 報表測試 |


---

# F-004 Reversal — 功能需求規格

**文件版本**: v0.1  
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
  "errors": [
    {
      "errorCode": "JOURNAL_ALREADY_REVERSED",
      "originalJournalId": "JNL-20260516-000012345",
      "reversalJournalId": "JNL-20260516-000012346"
    }
  ]
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


---

# F-005 v2 Balance Query & Snapshot（Raft 架構更新版）

**文件版本**: v0.2（基於 ADR-001 更新）  
**功能**: F-005 Balance Query & Snapshot  
**系統**: Next-Gen Internal Ledger Platform  
**狀態**: Draft for Review  
**變更摘要**: 實時 Balance 查詢路徑由 MySQL 改為 in-memory State Machine；快照機制維持不變，由 Learner 寫 MySQL

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

### 2.2 API（更新：支持 position 查詢）

```
GET /ledger/accounts/{accountId}/balances
    ?types=AVAILABLE_BALANCE,TRADE_AHEAD_BALANCE
    &currency=USD
    &position=CURRENT   # 可選，不帶則返回聚合結果
```

**position 參數說明**：

| 參數值 | 說明 |
|---|---|
| 省略 | 返回該 Balance Type 下所有 position 的聚合餘額 |
| `CURRENT` | 只返回 CURRENT position 的餘額 |
| `LOCKED` | 只返回 LOCKED position 的餘額 |
| `FROZEN` | 只返回 FROZEN position 的餘額 |

### 2.3 Response（更新：新增 positions 欄位）

```json
{
  "accountId": "CLIENT_ACC_001",
  "currency": "USD",
  "queryTime": "2026-05-16T10:35:00.000Z",
  "isRealtime": true,
  "dataSource": "STATE_MACHINE",
  "raftLeaderId": "node-001",
  "balances": [
    {
      "typeCode": "AVAILABLE_BALANCE",
      "amount": 200000.00,
      "positions": {
        "CURRENT": 150000.00,
        "LOCKED": 30000.00,
        "FROZEN": 20000.00
      },
      "allowNegative": false,
      "configVersion": 3,
      "lastJournalId": "JNL-20260516-000012345",
      "stateVersion": 1024
    },
    {
      "typeCode": "TRADE_AHEAD_BALANCE",
      "amount": -45000.00,
      "positions": {
        "CURRENT": -45000.00
      },
      "allowNegative": true,
      "negativeSemantics": "PRE_AUTHORIZED",
      "configVersion": 1,
      "stateVersion": 987
    }
  ]
}
```

新增欄位說明：
- `positions`：各 position 的餘額分佈（map），當查詢帶 `position` 參數時返回單一 position；不帶時返回所有 position 聚合
- `dataSource`：`STATE_MACHINE`（in-memory）/ `EOD_SNAPSHOT` / `JOURNAL_REPLAY`
- `raftLeaderId`：返回當前 Leader 節點 ID，便於診斷
- `stateVersion`：State Machine 的版本號（即 Raft Log Index），便於追蹤

---

## 3. 批量 Balance 查詢（不變，性能大幅提升）

```
POST /ledger/accounts/balances/batch
```

因為讀取 in-memory State Machine，批量查詢性能大幅提升：

| 指標 | v0.1（MySQL） | v0.2（State Machine） |
|---|---|---|
| 100 帳戶批量查詢 P95 | 50ms | ≤ 5ms |
| 200 帳戶批量查詢 P95 | 100ms | ≤ 10ms |

---

## 4. State Machine 內部資料結構

```java
// In-memory State Machine 的 Balance 儲存結構
// Key: AccountKey = (accountId, balanceType, currency)
// Value: BalanceEntry

class BalanceEntry {
    BigDecimal amount;        // 當前餘額
    long stateVersion;        // 對應的 Raft Log Index
    String lastJournalId;     // 最後一筆 Journal ID
    Instant lastUpdatedAt;    // 最後更新時間
}

ConcurrentHashMap<AccountKey, BalanceEntry> balanceStore;
```

**讀取是無鎖的**（Account Worker 寫，讀端只做快照讀），在 Java 21 下 ConcurrentHashMap 讀操作基本無競爭。

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


---

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
      "position": "CURRENT",
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

為支持上述查詢性能，需在 MySQL journal 和 journal_line 表建立以下索引。完整建表語句（含 `id BIGINT AUTO_INCREMENT PRIMARY KEY`、`created_at`、`updated_at`）見專案根目錄 `init.sql`。

```sql
-- journal 表
CREATE INDEX idx_request_id       ON journal (request_id);
CREATE INDEX idx_business_event_ref ON journal (business_event_ref);
CREATE INDEX idx_created_at       ON journal (created_at);

-- journal_line 表
CREATE INDEX idx_journal_id       ON journal_line (journal_id);
CREATE INDEX idx_account_id       ON journal_line (account_id);
CREATE INDEX idx_account_balance  ON journal_line (account_id, balance_type, position, currency);

-- account 表
CREATE INDEX idx_owner_id         ON account (owner_id);

-- account_balance 表（含 frozen_amount、locked_amount；position 作為分離欄位）
CREATE INDEX idx_account_id       ON account_balance (account_id);
CREATE INDEX idx_account_balance  ON account_balance (account_id, balance_type, position, currency);
```

> **account_balance 表結構變化（v0.3）**：
> - 新增 `position` 欄位（VARCHAR(16) NOT NULL，值為 CURRENT / LOCKED / FROZEN）
> - UNIQUE KEY 從 `(account_id, balance_type, currency)` 更新為 `(account_id, balance_type, position, currency)`
> - `frozen_amount` / `locked_amount` 保留為 legacy 欄位，但主要餘額追蹤改用 `position` 機制

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


---

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


---

# F-008 State Machine Design — 功能需求規格

**文件版本**: v0.1  
**功能**: F-008 State Machine Design  
**系統**: Next-Gen Internal Ledger Platform  
**狀態**: Draft for Review  
**依賴**: ADR-001（Raft + CQRS 架構）

---

## 1. 功能概述

State Machine 是 Ledger Platform 的核心計算單元，運行在 Raft Leader 節點上，負責：

1. **Apply Raft Log**：接收已 commit 的 Raft Command，執行帳務計算
2. **維護 in-memory Balance**：所有帳戶、所有 Balance Type 的最新餘額
3. **生成 Journal 記錄**：每次 apply 產生完整的 journal + journal_line
4. **持久化到 RocksDB**：確保宕機可恢復
5. **定期 Snapshot**：控制 Raft Log 增長，加快故障恢復

---

## 2. 資料結構

### 2.1 In-Memory Balance Store（更新：AccountBalanceKey 擴展）

```java
// 帳戶餘額 Key（v0.3 更新：新增 position 欄位）
record AccountBalanceKey(
    String accountId,
    String balanceType,
    String position,    // CURRENT / LOCKED / FROZEN
    String currency
) {}

// 帳戶餘額 Entry
record BalanceEntry(
    BigDecimal amount,          // 當前餘額
    long stateVersion,          // 最後更新的 Raft Log Index
    String lastJournalId,       // 最後一筆 Journal ID
    Instant lastUpdatedAt       // 最後更新時間
) {}

// Balance Store：無鎖讀，Account Worker 串行寫
ConcurrentHashMap<AccountBalanceKey, BalanceEntry> balanceStore;
```

**Position 查詢支援**：

```java
// 查詢某帳戶某 Balance Type 某幣種的所有 position 餘額
Map<String, BigDecimal> getPositions(String accountId, String balanceType, String currency) {
    return balanceStore.entrySet().stream()
        .filter(e -> e.getKey().accountId().equals(accountId)
                  && e.getKey().balanceType().equals(balanceType)
                  && e.getKey().currency().equals(currency))
        .collect(Collectors.toMap(
            e -> e.getKey().position(),
            e -> e.getValue().amount()
        ));
}

// 聚合計算總餘額
BigDecimal getAggregatedBalance(String accountId, String balanceType, String currency) {
    return getPositions(accountId, balanceType, currency).values().stream()
        .reduce(BigDecimal.ZERO, BigDecimal::add);
}
```

### 2.2 In-Memory Idempotency Store

```java
// 冪等 Key：requestId（含 posting / reversal / adjustment）
// Value：已完成的結果摘要
record IdempotencyEntry(
    String requestId,
    String status,          // COMPLETED / REJECTED
    String journalId,       // 成功時的 journalId
    List<String> errors,    // 失敗時的錯誤列表
    Instant completedAt
) {}

// TTL：保留 24 小時（可配置），避免 map 無限增長
// 實現：ConcurrentHashMap + 定期 eviction job
ConcurrentHashMap<String, IdempotencyEntry> idempotencyStore;
```

### 2.3 Account Metadata Store

```java
// 帳戶狀態（ACTIVE / FROZEN / CLOSED）
ConcurrentHashMap<String, AccountMeta> accountMetaStore;

record AccountMeta(
    String accountId,
    String status,
    Instant createdAt,
    Set<String> allowedBalanceTypes
) {}
```

### 2.4 Balance Type Config Store

```java
// Balance Type 配置（來自 F-001 Balance Type Registry）
// 在 State Machine 啟動時從 RocksDB 載入
// 配置變更時通過特殊 RaftCommand 更新
ConcurrentHashMap<String, BalanceTypeConfig> balanceTypeConfigStore;

record BalanceTypeConfig(
    String typeCode,
    boolean allowNegative,
    String negativeSemantics,   // PRE_AUTHORIZED / OVERDRAFT / etc.
    String signConvention,      // NORMAL_CREDIT / NORMAL_DEBIT
    String formula,             // 可選，FORMULA 類型時使用
    int configVersion
) {}
```

---

## 3. Raft Command 類型

所有帳務操作都被序列化成 RaftCommand，提交到 Raft Group：

| Command Type | 來源 | 說明 |
|---|---|---|
| `POSTING_CMD` | F-002 Posting API | 正常過帳 |
| `REVERSAL_CMD` | F-004 Reversal API | 反轉已有 Journal |
| `ADJUSTMENT_CMD` | F-003 Manual Adjustment API | 人工調帳 |
| `ACCOUNT_CREATE_CMD` | 帳戶管理 API | 創建新帳戶 |
| `ACCOUNT_FREEZE_CMD` | 帳戶管理 API | 凍結帳戶 |
| `BALANCE_TYPE_CONFIG_CMD` | F-001 Registry 管理 API | 更新 Balance Type 配置 |
| `SNAPSHOT_CMD` | 系統內部 | 觸發 State Machine Snapshot |

---

## 4. Apply 流程

### 4.1 POSTING_CMD Apply

```
輸入：PostingCommand {
  requestId, businessEventType, businessEventRef,
  valueDate, legs: [ { legId, lines: [ JournalLineCmd ] } ]
}

執行步驟：

1. 冪等檢查
   if idempotencyStore.contains(requestId):
     return idempotencyStore.get(requestId)  // 直接返回原結果

2. 帳戶狀態檢查
   for each accountId in command:
     if accountMetaStore.get(accountId).status != ACTIVE:
       return REJECTED(ACCOUNT_FROZEN)

3. Balance 校驗（讀 balanceStore，計算 after 值）
   for each JournalLineCmd:
     balanceTypeConfig = balanceTypeConfigStore.get(balanceType)
     currentBalance = balanceStore.get(AccountBalanceKey)
     afterBalance = compute(currentBalance, entryType, amount, signConvention)

     if !allowNegative && afterBalance < 0:
       return REJECTED(INSUFFICIENT_BALANCE)
     if allowNegative && afterBalance > 0:
       return REJECTED(CREDIT_EXCEEDS_LIMIT)

4. 生成 Journal
   journalId = generateJournalId(raftLogIndex)
   journal = Journal {
     journalId, journalType=NORMAL,
     requestId, businessEventType, businessEventRef,
     valueDate, status=CONFIRMED,
     createdAt=now()
   }

5. 生成 JournalLine
   for each JournalLineCmd:
     balanceBefore = balanceStore.get(key).amount
     balanceAfter = compute(balanceBefore, ...)
     journalLine = JournalLine {
       journalLineId, journalId, legId,
       accountId, balanceType, currency,
       entryType, amount,
       balanceBefore, balanceAfter,
       configVersion, createdAt=now()
     }

6. 原子更新 balanceStore
   for each journalLine:
     balanceStore.put(key, BalanceEntry(balanceAfter, raftLogIndex, journalId, now()))

7. 持久化到 RocksDB
   rocksDB.put(CF_JOURNAL, journalId, serialize(journal))
   for each journalLine:
     rocksDB.put(CF_JOURNAL_LINE, journalLineId, serialize(journalLine))
   for each AccountBalanceKey:
     rocksDB.put(CF_BALANCE, key, serialize(balanceStore.get(key)))
   // 以上三個 put 在同一 RocksDB WriteBatch，原子提交

8. 更新 idempotencyStore
   idempotencyStore.put(requestId, IdempotencyEntry(COMPLETED, journalId, ...))

9. 返回 PostingResult
```

### 4.2 REVERSAL_CMD Apply

```
1. 冪等檢查（同上）
2. 載入原 Journal + 所有 JournalLine
3. 校驗：原 Journal status = CONFIRMED（未被 reversed）
4. 生成 Reversal Journal
5. 生成鏡像 JournalLine（DEBIT ↔ CREDIT 互換）
6. 不做符號語義校驗（Reversal 是對沖，必然合法）
7. 原子更新 balanceStore（回滾相關餘額）
8. 更新原 Journal status = REVERSED
9. 寫 RocksDB，更新 idempotencyStore，返回結果
```

### 4.3 ADJUSTMENT_CMD Apply

```
1. 冪等檢查（同上）
2. 讀 balanceStore 做符號語義校驗（必須符合 allowNegative 規則）
3. 生成 Adjustment Journal（journalType=MANUAL_ADJUSTMENT）
4. 原子更新 balanceStore
5. 寫 RocksDB，更新 idempotencyStore，返回結果
```

---

## 5. RocksDB 儲存設計

### 5.1 Column Family 設計

```
CF_JOURNAL          存放 Journal 頭部記錄
CF_JOURNAL_LINE     存放 JournalLine 記錄
CF_BALANCE          存放帳戶餘額快照（最新值）
CF_IDEMPOTENCY      存放冪等記錄（requestId → result）
CF_ACCOUNT_META     存放帳戶 metadata
CF_BALANCE_TYPE     存放 Balance Type 配置
CF_SM_SNAPSHOT      存放 State Machine Snapshot
```

### 5.2 Key 設計

```
CF_JOURNAL:
  Key: journal_id（字典序）
  → 按 journal_id 可快速點查

CF_JOURNAL_LINE:
  Key: journal_id + "#" + journal_line_id
  → 按 journal_id prefix 掃描可取出一筆 Journal 的所有 lines

CF_BALANCE:
  Key: account_id + "#" + balance_type + "#" + position + "#" + currency
  → 按 account_id + "#" + balance_type + "#" prefix 掃描可取出一個帳戶某 Balance Type 的所有 position 餘額
  → 按 account_id prefix 掃描可取出一個帳戶的所有 balance

CF_IDEMPOTENCY:
  Key: request_id
  Value: 序列化的 IdempotencyEntry + TTL 時間戳
```

### 5.3 WriteBatch 保證原子性

每次 apply 一個 RaftCommand，對 RocksDB 的所有寫操作打包成一個 `WriteBatch`，確保 journal / journal_line / balance 三者原子落盤：

```java
WriteBatch batch = new WriteBatch();
batch.put(CF_JOURNAL, journalKey, journalBytes);
batch.put(CF_JOURNAL_LINE, line1Key, line1Bytes);
batch.put(CF_JOURNAL_LINE, line2Key, line2Bytes);
batch.put(CF_BALANCE, balKey1, balBytes1);
batch.put(CF_BALANCE, balKey2, balBytes2);
rocksDB.write(writeOptions, batch);
// WriteBatch 寫入是原子的：要嘛全部成功，要嘛全部失敗
```

---

## 6. State Machine Snapshot

### 6.1 Snapshot 觸發條件

| 觸發條件 | 說明 |
|---|---|
| Raft Log 達到 100,000 條 | 自動觸發，防止 Log 無限增長 |
| 帳期關閉 | EOD 時強制做一次 Snapshot，作為對帳基準 |
| 手動觸發 | 管理 API，用於升級或災難恢復前 |

### 6.2 Snapshot 內容

```
Snapshot 包含：
  1. 所有帳戶的 balanceStore 完整快照
  2. 所有帳戶的 accountMetaStore 快照
  3. 所有 Balance Type 配置快照
  4. 對應的 Raft Log Index（lastAppliedIndex）
  5. 生成時間戳

Snapshot 格式：
  序列化為 Protobuf / Kryo，寫入 CF_SM_SNAPSHOT
  同時通過 SOFAJRaft 的 SnapshotWriter 保存到本地磁碟
  Follower 可直接複製 Snapshot，加速新節點加入
```

### 6.3 故障恢復流程

```
Leader 宕機 → 新 Leader 選出

新 Leader 恢復步驟：
  1. 從 CF_SM_SNAPSHOT 載入最新 Snapshot
     → 恢復 balanceStore / accountMetaStore / balanceTypeConfigStore
  2. 從 Raft Log replay lastAppliedIndex 之後的所有 Command
     → 逐一 apply，補齊 Snapshot 後的所有變化
  3. State Machine 完全恢復，開始服務

恢復時間估算：
  Snapshot 載入：< 10 秒（取決於帳戶數量）
  Raft Log replay：< 30 秒（100,000 條 log × 0.3ms/cmd）
  總計：< 1 分鐘（正常場景）
```

---

## 7. Learner 同步設計

### 7.1 Learner 角色

Learner 是 Raft 的 non-voting 成員，接收 Leader 的 Raft Log 但不參與選舉：

```
Raft Leader
    │
    │ replicate Raft Log（非同步，不阻塞 Quorum）
    ▼
Raft Learner
    │
    ▼
  Learner State Machine
    │ apply Raft Log，把帳務事件轉成 MySQL 寫操作
    ▼
  MySQL View Layer
    ├─ journal（供 F-006 Journal Query）
    ├─ journal_line（供 F-006 Journal Query）
    ├─ account（帳戶資料，供 F-010 Account Query）
    ├─ account_balance（供 F-007 Reconciliation；含 frozen_amount、locked_amount）
    ├─ balance_type_registry（Balance Type 配置）
    └─ balance_snapshot（供 F-005 As-of Query）
```

### 7.2 同步延遲

- 正常情況：Learner 落後 Leader < 1 秒
- 高負載：< 5 秒（Learner 有 write buffer 批量寫 MySQL）
- 查詢端會標明 `dataSource`，讓 caller 知道數據是否可能略舊

### 7.3 Learner MySQL 寫入設計

為避免 Learner 成為瓶頸，Learner 採用批量寫入策略：

```
Learner 緩衝 500ms 或 1000 條 Raft Log（先到者觸發）
→ 批量 INSERT INTO journal_line VALUES (...)
→ 批量 UPSERT account_balance SET amount, account_seq, last_journal_id
  （frozen_amount、locked_amount 保留不覆蓋）
→ commit
```

---

## 8. 冷帳戶管理

帳戶數量可能達百萬，不可能所有帳戶都常駐記憶體：

```
Active Set：過去 24 小時有交易的帳戶 → 常駐 balanceStore（記憶體）
Inactive Set：超過 24 小時無交易 → 從 balanceStore evict，只保留 RocksDB

讀取 Inactive 帳戶餘額：
  1. 查 balanceStore → miss
  2. 從 RocksDB CF_BALANCE 讀取（< 1ms）
  3. 載入到 balanceStore（warm up）

寫入 Inactive 帳戶：
  Account Worker 啟動時先從 RocksDB load balance
  → 然後正常執行 apply 流程
```

---

## 9. 性能目標

| 操作 | 目標 | 說明 |
|---|---|---|
| Balance 讀取（Active 帳戶） | < 0.1ms | ConcurrentHashMap 直讀 |
| Balance 讀取（Inactive 帳戶） | < 1ms | RocksDB 讀取 |
| RaftCommand Apply（單帳戶） | < 1ms | State Machine + RocksDB WriteBatch |
| RaftCommand Apply（多帳戶 RFQ） | < 2ms | 多帳戶 WriteBatch |
| State Machine Snapshot（100萬帳戶） | < 30s | 批量序列化 |
| 故障恢復（Snapshot + 10萬條 Replay） | < 1 min | 見 6.3 |

---

## 10. 驗收標準

| # | 驗收條件 | 測試方式 |
|---|---|---|
| AC-01 | Posting 後 in-memory Balance 立即更新，無延遲 | 功能測試 |
| AC-02 | 相同 requestId 在 idempotencyStore 命中，直接返回原結果，不重複 apply | 冪等測試 |
| AC-03 | RocksDB WriteBatch 原子性：模擬寫到一半崩潰，重啟後資料一致 | 故障測試 |
| AC-04 | State Machine Snapshot 後，從 Snapshot 恢復的 Balance 與 Snapshot 前完全一致 | 恢復測試 |
| AC-05 | 故障恢復（Snapshot + Replay）在 1 分鐘內完成 | 性能測試 |
| AC-06 | Inactive 帳戶被 evict 後，下次訪問能從 RocksDB 正確 warm up | 功能測試 |
| AC-07 | Learner 同步延遲在正常負載下 < 1 秒 | 一致性測試 |
| AC-08 | 100 萬帳戶的 State Machine，Balance 讀取 P95 ≤ 0.1ms（Active）/ ≤ 1ms（Inactive） | 性能測試 |
| AC-09 | BALANCE_TYPE_CONFIG_CMD apply 後，新的 allowNegative 規則立即生效 | 功能測試 |
| AC-10 | Multi-Account RaftCommand（RFQ 場景）在 State Machine 中原子 apply，不出現部分更新 | 原子性測試 |


---

# F-009 Accounting Period / EOD — 功能需求規格

**文件版本**: v0.1  
**功能**: F-009 Accounting Period & EOD 關期  
**系統**: Next-Gen Internal Ledger Platform  
**狀態**: Draft for Review  
**依賴**: ADR-001、F-005 Balance Snapshot、F-007 Reconciliation

---

## 1. 功能概述

Accounting Period 管理帳務切期邏輯：控制哪個帳期開放過帳、哪個帳期已關閉、以及 EOD（End of Day）關期時的快照與對帳觸發。

---

## 2. 帳期狀態機

```
      開盤
        │
        ▼
     [OPEN]         ← 正常過帳均落在此帳期
        │
  EOD 觸發關期
        │
        ▼
   [CLOSING]        ← 禁止新 Posting，等待 EOD 任務完成
        │
  EOD 任務完成
        │
        ▼
    [CLOSED]        ← 不可過帳；跨期 Reversal 需標記 crossPeriod=true
        │
  下一帳期開盤
        │
        ▼
   [OPEN] (T+1)
```

---

## 3. EOD 任務序列

帳期關閉時，系統按以下**嚴格順序**執行 EOD 任務：

```
Step 1  停止接受新 Posting（帳期狀態 → CLOSING）
Step 2  等待所有 in-flight Raft Command 完成（drain queue）
Step 3  觸發 State Machine Snapshot（F-008）
Step 4  Learner 確認 MySQL View Layer 已追上 Snapshot Index
Step 5  執行 L1 對帳（Journal 借貸平衡 + Balance 一致性）
Step 6  執行 L2 對帳（子帳對總帳）
Step 7  生成 EOD Balance Snapshot（所有帳戶當前餘額）
Step 8  生成 Reconciliation Report
Step 9  帳期狀態 → CLOSED
Step 10 新帳期 OPEN（T+1）
```

任何步驟失敗：發出告警，人工介入，不自動跳過。

---

## 4. 帳期配置

| 欄位 | 說明 |
|---|---|
| `periodId` | 帳期唯一 ID（如 `2026-05-16`） |
| `openTime` | 帳期開放時間 |
| `scheduledCloseTime` | 計劃關期時間（如每日 23:30） |
| `actualCloseTime` | 實際關期完成時間 |
| `status` | OPEN / CLOSING / CLOSED |
| `eodTaskStatus` | 各 EOD 子任務狀態 JSON |

---

## 5. 跨帳期規則

| 情況 | 處理 |
|---|---|
| 對已 CLOSED 帳期做 Posting | 拒絕，返回 `PERIOD_CLOSED` |
| 對已 CLOSED 帳期做 Reversal | 允許，但標記 `crossPeriod=true` |
| 對已 CLOSED 帳期做 Manual Adjustment | 允許，但需額外審批（Checker 需在審批備注確認跨期） |

---

## 6. API 設計

```
# 查詢帳期列表
GET /ledger/accounting-periods?status=OPEN

# 手動觸發 EOD（測試或補跑）
POST /ledger/accounting-periods/{periodId}/eod/trigger

# 查詢 EOD 任務狀態
GET /ledger/accounting-periods/{periodId}/eod/status
```

---

## 7. 驗收標準

| # | 驗收條件 | 測試方式 |
|---|---|---|
| AC-01 | EOD 任務按嚴格順序執行，任一步驟失敗不繼續 | 功能測試 |
| AC-02 | CLOSING 期間新 Posting 請求返回 `PERIOD_CLOSED` | 功能測試 |
| AC-03 | 跨帳期 Reversal 標記 `crossPeriod=true` | 功能測試 |
| AC-04 | EOD 完成後 EOD Balance Snapshot 與 State Machine 一致 | 對帳測試 |
| AC-05 | EOD 全流程（含 L1/L2 對帳）在 30 分鐘內完成 | 性能測試 |


---

# F-010 Account Management — 功能需求規格

**文件版本**: v0.1  
**功能**: F-010 Account Management（帳戶管理）  
**系統**: Next-Gen Internal Ledger Platform  
**狀態**: Draft for Review  
**依賴**: ADR-001、F-001 Balance Type Registry、F-008 State Machine

---

## 1. 功能概述

Account Management 管理帳戶的完整生命週期，包括創建、Balance Type 初始化、凍結、解凍、關閉，並維護帳戶 metadata 供 Posting、Balance Query、Reconciliation 使用。

---

## 2. 帳戶類型

| 類型 | 說明 | 示例 |
|---|---|---|
| `CLIENT` | 客戶帳戶 | `CLIENT_ACC_001` |
| `COMPANY` | 公司自有帳戶（含 RFQ 對手方帳戶） | `COMPANY_FX_ACC` |
| `SUSPENSE` | 過渡帳戶（清算差異暫存） | `SUSPENSE_USD_001` |
| `NOSTRO` | 我方在對手行的帳戶 | `NOSTRO_HSBC_USD` |
| `CONTROL` | 總帳控制帳戶（L2 對帳用） | `CONTROL_CLIENT_USD` |

---

## 3. 帳戶狀態機

```
   創建
    │
    ▼
 [ACTIVE]  ←──────────────────┐
    │                         │
    │ 凍結                  解凍
    ▼                         │
[FROZEN] ─────────────────────┘
    │
    │ 關閉（需餘額為零）
    ▼
 [CLOSED]  ← 不可過帳，不可解凍，只可查詢
```

---

## 4. API 設計

### 4.1 創建帳戶

```
POST /ledger/accounts
```

**Request Body**

| 欄位 | 類型 | 必填 | 說明 |
|---|---|---|---|
| `accountId` | `string` | ✅ | 帳戶唯一 ID（由調用方指定，不可重複） |
| `accountType` | `enum` | ✅ | CLIENT / COMPANY / SUSPENSE / NOSTRO / CONTROL |
| `displayName` | `string` | ✅ | 顯示名稱 |
| `ownerId` | `string` | ❌ | 帳戶所有者 ID（客戶帳戶必填） |
| `balanceInitializations` | `list` | ✅ | 初始化的 balance type + currency（初始餘額默認 0） |
| `metadata` | `map` | ❌ | 擴展欄位 |

**balanceInitializations 結構**

```json
[
  { "balanceType": "AVAILABLE_BALANCE", "position": "CURRENT", "currency": "USD" },
  { "balanceType": "AVAILABLE_BALANCE", "position": "LOCKED", "currency": "USD" },
  { "balanceType": "AVAILABLE_BALANCE", "position": "CURRENT", "currency": "HKD" },
  { "balanceType": "TRADE_AHEAD_BALANCE", "position": "CURRENT", "currency": "USD" }
]
```

> **position 必填**：v0.3 起，balanceInitializations 需明確指定 position。若不需多 position，使用 `CURRENT` 作為預設值。

**帳戶創建走 Raft**：帳戶 metadata 需在 State Machine 中創建，確保所有節點一致。

### 4.2 凍結 / 解凍 / 關閉

```
POST /ledger/accounts/{accountId}/freeze
POST /ledger/accounts/{accountId}/unfreeze
POST /ledger/accounts/{accountId}/close
```

- 凍結 / 解凍走 Raft（影響 State Machine 帳戶狀態）
- 關閉需校驗所有 Balance Type 餘額為零，否則拒絕

### 4.3 查詢帳戶

```
GET /ledger/accounts/{accountId}
GET /ledger/accounts?accountType=CLIENT&ownerId=CUST-001
```

讀 MySQL View Layer（最終一致性）。

### 4.4 新增 Balance Type + Position 到已有帳戶

```
POST /ledger/accounts/{accountId}/balance-types
{ "balanceType": "BROKERAGE_BALANCE", "position": "CURRENT", "currency": "USD" }
```

走 Raft，在 State Machine 中初始化新 balance entry（初始值 0）。

---

## 5. 校驗規則

| # | 規則 | 錯誤碼 |
|---|---|---|
| V-01 | `accountId` 全局唯一 | `ACCOUNT_ALREADY_EXISTS` |
| V-02 | `balanceType` 必須在 F-001 Registry 中存在 | `BALANCE_TYPE_NOT_FOUND` |
| V-03 | 關閉帳戶時所有 Balance 必須為零 | `ACCOUNT_HAS_NON_ZERO_BALANCE` |
| V-04 | 已 CLOSED 帳戶不可解凍或重新激活 | `ACCOUNT_CLOSED` |
| V-05 | CLIENT 類型帳戶創建時 `ownerId` 必填 | `MISSING_OWNER_ID` |

---

## 6. 驗收標準

| # | 驗收條件 | 測試方式 |
|---|---|---|
| AC-01 | 創建帳戶後，State Machine 立即可查到帳戶 metadata | 功能測試 |
| AC-02 | 凍結帳戶後，Posting 到該帳戶返回 `ACCOUNT_FROZEN` | 功能測試 |
| AC-03 | 解凍後，Posting 正常執行 | 功能測試 |
| AC-04 | 帳戶餘額不為零時關閉，返回 `ACCOUNT_HAS_NON_ZERO_BALANCE` | 功能測試 |
| AC-05 | 新增 Balance Type 後，可立即對該 type 做 Posting | 功能測試 |


---

# NFR — 非功能需求規格

**文件版本**: v0.1  
**功能**: 非功能需求（NFR）  
**系統**: Next-Gen Internal Ledger Platform  
**狀態**: Draft for Review

---

## 1. 性能（Performance）

| 指標 | 目標 | 測試條件 |
|---|---|---|
| Posting P95 延遲 | ≤ 3ms | 1000 並發，含 hotspot 帳戶（COMPANY_ACC） |
| Posting P99 延遲 | ≤ 10ms | 同上 |
| Balance Query P95（Active 帳戶） | ≤ 2ms | 讀 in-memory State Machine |
| Balance Query P95（Inactive 帳戶） | ≤ 5ms | 讀 RocksDB warm-up |
| Journal 點查 P95 | ≤ 10ms | MySQL View Layer，索引命中 |
| 帳戶流水查詢 P95（50 條） | ≤ 30ms | MySQL View Layer |
| Manual Adjustment Approve P95 | ≤ 10ms | 走 Raft |
| Reversal P95 | ≤ 5ms | 走 Raft |
| Learner 同步延遲（正常負載） | ≤ 1s | Raft Leader → MySQL View Layer |
| EOD 全流程（含 L1/L2 對帳） | ≤ 30 分鐘 | 每日 100 萬筆 Journal |

---

## 2. 吞吐量（Throughput）

| 指標 | 目標 |
|---|---|
| Posting TPS（峰值） | ≥ 10,000 TPS |
| Balance Query QPS | ≥ 50,000 QPS |
| Journal Query QPS | ≥ 5,000 QPS |

---

## 3. 可用性（Availability）

| 指標 | 目標 |
|---|---|
| 系統可用性（年度） | ≥ 99.99%（年停機 ≤ 52 分鐘） |
| Raft Leader 故障恢復時間（RTO） | ≤ 30 秒（含選舉 + State Machine 恢復） |
| 計劃維護停機視窗 | 每月 ≤ 30 分鐘（非 EOD 時段） |
| 多 AZ 部署 | 3 節點跨 AZ，允許 1 個 AZ 故障不影響服務 |

---

## 4. 數據持久性（Durability）

| 指標 | 目標 |
|---|---|
| RPO（Recovery Point Objective） | 0（Raft Quorum commit 即持久，不丟已確認帳務） |
| RTO（Recovery Time Objective） | ≤ 1 分鐘（Snapshot + Replay） |
| Journal 保留年限 | ≥ 7 年（合規要求） |
| Audit Log 保留年限 | ≥ 7 年 |

---

## 5. 一致性（Consistency）

| 場景 | 一致性級別 |
|---|---|
| Posting / Reversal / Adjustment 寫入 | **強一致性**（Raft Quorum commit） |
| Balance Query（實時） | **強一致性**（讀 Leader in-memory State Machine） |
| Journal Query | **最終一致性**（Learner 同步，延遲 ≤ 1s） |
| Reconciliation 對帳 | **最終一致性**（T+0 日內完成） |

---

## 6. 不可變性（Immutability）

- 所有已過帳 JournalLine **禁止 UPDATE / DELETE**
- 只允許 append（新增 Journal / Reversal / Adjustment）
- RocksDB 和 MySQL View Layer 均以 append-only 模式維護 JournalLine

---

## 7. 冪等性（Idempotency）

- 所有寫操作（Posting / Reversal / Adjustment Approve）均支持冪等
- 冪等 Key：`requestId`（UUID v7），TTL ≥ 24 小時
- 重試相同 `requestId` 返回原始結果，不重複入帳
- In-memory idempotency store + DB unique constraint 雙層保障

---

## 8. 安全性（Security）

| 要求 | 說明 |
|---|---|
| 身份認證 | 所有 API 需通過 JWT / mTLS 認證 |
| 授權 | RBAC，區分只讀角色（Viewer）、操作角色（Operator）、審批角色（Checker）、管理角色（Admin） |
| Maker-Checker | Manual Adjustment 強制雙人複核，不可繞過 |
| 審計日誌 | 所有寫操作記錄操作員、時間、IP、traceId，保留 7 年 |
| 敏感數據 | 帳戶 ID、金額在日誌中脫敏處理 |

---

## 9. 可觀測性（Observability）

| 要求 | 說明 |
|---|---|
| 分布式追蹤 | 所有請求帶 traceId / spanId，接入 Jaeger / Zipkin |
| Metrics | Prometheus 暴露 TPS、P50/P95/P99 延遲、Queue 積壓、Raft term、Learner lag |
| 告警 | Posting P99 > 50ms、Queue 積壓 > 1000、Learner lag > 10s、L1 對帳失敗 → PagerDuty |
| 日誌 | 結構化 JSON 日誌，帶 journalId、requestId、accountId、traceId |
| 每筆帳務可追溯 | 任意餘額變化可在 5 分鐘內追到 source event、operator、rule version、journal chain |

---

## 10. 容量規劃（Capacity）

| 指標 | 假設值 | 說明 |
|---|---|---|
| 帳戶總數 | 1,000,000 | Active 帳戶約 100,000 常駐記憶體 |
| 日均 Journal 數 | 5,000,000 | 每日 500 萬筆帳務 |
| 每筆 Journal 平均 JournalLine 數 | 4 | RFQ 場景通常 4 條 |
| 日均 JournalLine 數 | 20,000,000 | |
| RocksDB 日增量 | ~10 GB | 估算 500 bytes / JournalLine |
| MySQL View Layer 日增量 | ~20 GB | 含索引 |
| State Machine 記憶體（Active 帳戶） | ~2 GB | 100,000 帳戶 × 5 BalanceType × ~4KB |
| Raft Log Snapshot 間隔 | 100,000 條 | 約每 1 分鐘一次快照（10,000 TPS 峰值） |

---

## 11. 災難恢復（DR）

| 要求 | 說明 |
|---|---|
| 多 AZ 部署 | 3 個 Raft 節點分佈在 3 個 AZ |
| 跨 DC 容災 | Raft Learner 可部署在異地 DC，作為 DR 節點 |
| RocksDB 備份 | 每日全量 RocksDB checkpoint 備份到對象存儲（S3 / OSS） |
| 恢復演練 | 每季度進行一次完整 DR 演練，驗證 RTO ≤ 1 分鐘 |

---

## 12. 技術約束

| 約束 | 說明 |
|---|---|
| 語言 / 框架 | Java 21 + Spring Boot 3，使用 Virtual Threads |
| Raft 庫 | SOFAJRaft（評估 Apache Ratis 作為備選） |
| 本地持久化 | RocksDB（Java API） |
| View Layer DB | MySQL 8.0+（MyBatis，禁止 ORM） |
| 消息總線 | Kafka（Learner 同步輸出帳務事件供下游系統消費） |
| 禁止使用 | Hibernate / JPA / Redis（寫路徑）/ 直接寫 MySQL 繞過 Raft |


---


# F-008 State Machine Design — 功能需求規格

**文件版本**: v0.2
**功能**: F-008 State Machine Design
**系統**: Next-Gen Internal Ledger Platform
**狀態**: Draft for Review
**依賴**: ADR-001（Raft + CQRS 架構）

> **v0.2 變更摘要**：`BalanceEntry` 新增 `accountSeq` 欄位（per-account 單調遞增序號），Apply 流程同步更新，Snapshot 序列化要求補充，新增 AC-11。

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

### 2.1 In-Memory Balance Store

```java
// 帳戶餘額 Key
record AccountBalanceKey(
    String accountId,
    String balanceType,
    String currency
) {}

// 帳戶餘額 Entry
record BalanceEntry(
    BigDecimal amount,          // 當前餘額
    long stateVersion,          // 最後更新的 Raft Log Index
    long accountSeq,            // 【v0.2 新增】per-account 單調遞增序號
                                // 維度：accountId + balanceType + currency
                                // 每次 balance 變動（Posting/Reversal/Adjustment）+1
                                // 從 1 開始，與 raftLogIndex 無關
    String lastJournalId,       // 最後一筆 Journal ID
    Instant lastUpdatedAt       // 最後更新時間
) {}

// Balance Store：無鎖讀，Account Worker 串行寫
ConcurrentHashMap<AccountBalanceKey, BalanceEntry> balanceStore;
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

6. 原子更新 balanceStore（含 accountSeq 遞增）【v0.2 更新】
   for each journalLine:
     currentEntry = balanceStore.get(key)
     nextSeq = (currentEntry != null) ? currentEntry.accountSeq() + 1 : 1
     balanceStore.put(key, BalanceEntry(
         balanceAfter, raftLogIndex, nextSeq, journalId, now()
     ))
   // REVERSAL_CMD 和 ADJUSTMENT_CMD 的 balanceStore 更新同樣遞增 accountSeq

7. 持久化到 RocksDB
   rocksDB.put(CF_JOURNAL, journalId, serialize(journal))
   for each journalLine:
     rocksDB.put(CF_JOURNAL_LINE, journalLineId, serialize(journalLine))
   for each AccountBalanceKey:
     rocksDB.put(CF_BALANCE, key, serialize(balanceStore.get(key)))
   // 以上三個 put 在同一 RocksDB WriteBatch，原子提交
   // CF_BALANCE 序列化的 BalanceEntry 已含 accountSeq

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
7. 原子更新 balanceStore（回滾相關餘額，accountSeq 同樣遞增）
8. 更新原 Journal status = REVERSED
9. 寫 RocksDB，更新 idempotencyStore，返回結果
```

### 4.3 ADJUSTMENT_CMD Apply

```
1. 冪等檢查（同上）
2. 讀 balanceStore 做符號語義校驗（必須符合 allowNegative 規則）
3. 生成 Adjustment Journal（journalType=MANUAL_ADJUSTMENT）
4. 原子更新 balanceStore（accountSeq 同樣遞增）
5. 寫 RocksDB，更新 idempotencyStore，返回結果
```

---

## 5. RocksDB 儲存設計

### 5.1 Column Family 設計

```
CF_JOURNAL          存放 Journal 頭部記錄
CF_JOURNAL_LINE     存放 JournalLine 記錄
CF_BALANCE          存放帳戶餘額快照（最新值，含 accountSeq）
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
  Key: account_id + "#" + balance_type + "#" + currency
  → 按 account_id prefix 掃描可取出一個帳戶的所有 balance

CF_IDEMPOTENCY:
  Key: request_id
  Value: 序列化的 IdempotencyEntry + TTL 時間戳
```

### 5.3 WriteBatch 保證原子性

每次 apply 一個 RaftCommand，對 RocksDB 的所有寫操作打包成一個 `WriteBatch`，確保 journal / journal_line / balance（含 accountSeq）三者原子落盤：

```java
WriteBatch batch = new WriteBatch();
batch.put(CF_JOURNAL, journalKey, journalBytes);
batch.put(CF_JOURNAL_LINE, line1Key, line1Bytes);
batch.put(CF_JOURNAL_LINE, line2Key, line2Bytes);
batch.put(CF_BALANCE, balKey1, balBytes1);  // BalanceEntry 含 accountSeq
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

### 6.2 Snapshot 內容【v0.2 更新】

```
Snapshot 包含：
  1. 所有帳戶的 balanceStore 完整快照（含每個 BalanceEntry 的 accountSeq）
  2. 所有帳戶的 accountMetaStore 快照
  3. 所有 Balance Type 配置快照
  4. 對應的 Raft Log Index（lastAppliedIndex）
  5. 生成時間戳

Snapshot 格式：
  序列化為 Protobuf / Kryo，寫入 CF_SM_SNAPSHOT
  同時通過 SOFAJRaft 的 SnapshotWriter 保存到本地磁碟
  Follower 可直接複製 Snapshot，加速新節點加入

⚠️ 重要：accountSeq 必須納入 BalanceEntry 的序列化 schema。
   若漏掉此欄位，重啟從 Snapshot 恢復後 accountSeq 會歸零，
   導致下游 consumer 誤判 event stream gap。

Protobuf 示例：
  message BalanceEntry {
    string amount         = 1;
    int64  stateVersion   = 2;
    int64  accountSeq     = 3;  // 必須包含
    string lastJournalId  = 4;
    int64  lastUpdatedAt  = 5;
  }
```

### 6.3 故障恢復流程

```
Leader 宕機 → 新 Leader 選出

新 Leader 恢復步驟：
  1. 從 CF_SM_SNAPSHOT 載入最新 Snapshot
     → 恢復 balanceStore（含 accountSeq）/ accountMetaStore / balanceTypeConfigStore
  2. 從 Raft Log replay lastAppliedIndex 之後的所有 Command
     → 逐一 apply，補齊 Snapshot 後的所有變化（accountSeq 繼續遞增）
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
    ├─ account_balance（供 F-007 Reconciliation）
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
→ 批量 UPDATE account_balance SET ...
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
  2. 從 RocksDB CF_BALANCE 讀取（< 1ms），含 accountSeq
  3. 載入到 balanceStore（warm up）

寫入 Inactive 帳戶：
  Account Worker 啟動時先從 RocksDB load balance（含 accountSeq）
  → 然後正常執行 apply 流程，accountSeq 從 RocksDB 的值繼續遞增
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
| AC-11 | BalanceEntry 的 accountSeq 納入 State Machine Snapshot 序列化；重啟從 Snapshot 恢復後，accountSeq 與恢復前完全一致，不得重置為 0 | 故障恢復測試 |

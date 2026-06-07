# F-012 Projection Service — Kafka → MySQL View Layer

**版本**: v0.1
**功能**: F-012 Projection Service
**系統**: Next-Gen Internal Ledger Platform
**依賴**: F-011 Balance Change Event, F-011b Posting Completion Event, MySQL View Layer

---

## 1. 功能概述

Projection Service 是 CQRS 架構中的 **讀路徑同步服務**。它獨立部署，從 Kafka 消費帳務事件，並投影到 MySQL View Layer，供 Journal Query (F-006)、Reconciliation (F-007) 等查詢使用。

**核心原則**：
- **獨立部署**：不嵌入 Ledger 節點，crash 不影響入帳
- **At-least-once 消費**：通過 idempotent insert 保證不重複寫入
- **投影數據**：journal + journal_line + account + account_balance；讀路徑 API 直接查詢 MySQL 分擔 Raft 節點壓力

---

## 2. 架構位置

```
Ledger Node (Leader)                Ledger Node (Follower)
     │                                     │
     ├─ StateMachine.apply()               ├─ StateMachine.apply()
     ├─ RocksDB persist                    ├─ RocksDB persist
     └─ Kafka publish ──────────────┐      └─ Kafka publish ───┐
                                    │                           │
                              ┌─────▼─────┐              ┌──────▼─────┐
                              │   Kafka   │              │   Kafka    │
                              │  Topic    │              │  Topic     │
                              └─────┬─────┘              └──────┬─────┘
                                    │                           │
                                    └──────────┬────────────────┘
                                               │
                                    ┌──────────▼──────────┐
                                    │ Projection Service  │
                                    │ (獨立部署 :8089)      │
                                    │                     │
                                    │ Kafka Consumer Group │
                                    │ → MySQL INSERT      │
                                    └──────────┬──────────┘
                                               │
                                        ┌──────▼──────┐
                                        │    MySQL    │
                                        │ (View Layer)│
                                        └─────────────┘
```

---

## 3. Kafka Consumer 設計

### 3.1 Consumer 配置

```yaml
kafka:
  bootstrap-servers: ledger-kafka:9092
  consumer:
    group-id: ledger-projection
    auto-offset-reset: earliest
  topics:
    account-created: ledger.account.v1
    balance-change: ledger.balance.change.v1
```

### 3.2 Consumer Group 策略

- **Group ID**: `ledger-projection`（多實例共享）
- **Partition 分配**: 64 partitions，支援最多 64 個 Projection 實例水平擴展
- **順序保證**: 同一個 `accountId:balanceType:currency` 的事件必在同一 partition（按 partition key 路由），保證順序投影

---

## 4. 投影邏輯

### 4.1 AccountCreatedEvent → MySQL

```
收到 AccountCreatedEvent {
  accountId, accountType, displayName, ownerId,
  status, balanceTypes, createdAt, ...
}
       │
       ▼
1. UPSERT INTO account (idempotent: ON DUPLICATE KEY UPDATE)
   - account_id, account_type, display_name, owner_id, status, created_at

2. 若 account_balance 記錄不存在，自動為每個 balanceType 初始化一條記錄：
   - account_id, balance_type, currency=default, amount=0,
     frozen_amount=0, locked_amount=0, account_seq=0
```

### 4.2 BalanceChangeEvent → MySQL

```
收到 BalanceChangeEvent {
  journalId, journalLineId, commandType,
  accountId, balanceType, currency,
  entryType, amount, preBalance, postBalance,
  accountSeq, prevAccountSeq, valueDate, ...
}
       │
       ▼
1. INSERT INTO journal (idempotent: ON DUPLICATE KEY IGNORE)
   - journal_id, journal_type, request_id, business_event_type, business_event_ref,
     value_date, status, cross_period, created_at

2. INSERT INTO journal_line (idempotent: ON DUPLICATE KEY IGNORE)
   - journal_line_id, journal_id, leg_id, account_id, balance_type, currency,
     entry_type, amount, balance_before, balance_after, config_version, created_at

3. UPSERT INTO account_balance (idempotent: ON DUPLICATE KEY UPDATE)
   - account_id, balance_type, currency, amount, account_seq, last_journal_id
   - 同時保留 frozen_amount、locked_amount 不被覆蓋

4. Log projection status (DEBUG level)
```

### 4.3 Balance Type Registry 初始化

Projection Service 啟動時，自動檢查並寫入預設 balance_type_registry 記錄（如 `AVAILABLE`、`PENDING_SETTLEMENT`），供查詢與審計使用。

### 4.4 冪等性保證

```sql
-- journal 表以 journal_id 為 UNIQUE KEY，id 為自增主鍵
-- 重複消費同一事件時，INSERT ... ON DUPLICATE KEY 直接跳過，不拋出異常
INSERT INTO journal (...) VALUES (...);

-- journal_line 表以 journal_line_id 為 UNIQUE KEY，id 為自增主鍵
-- 同樣 idempotent
INSERT INTO journal_line (...) VALUES (...);

-- account 表以 account_id 為 UNIQUE KEY
-- 重複創建時更新 account_type、status、display_name
INSERT INTO account (...) VALUES (...)
ON DUPLICATE KEY UPDATE account_type = VALUES(account_type), status = VALUES(status), display_name = VALUES(display_name);

-- account_balance 表以 (account_id, balance_type, currency) 為 UNIQUE KEY
INSERT INTO account_balance (...) VALUES (...)
ON DUPLICATE KEY UPDATE amount = VALUES(amount), account_seq = VALUES(account_seq), last_journal_id = VALUES(last_journal_id);
```

### 4.5 寫入路徑：50ms Micro-batch Flush（v0.16 變更）

`projection_event_log` 與 `journal_line` 在第 4.2 節描述中是「逐條
INSERT」，實際上線後觀察到這是 MySQL projection 落後的主因：每個
Kafka poll 最多 2000 個 event，每個 event 一條 `insertJournalLine` +
一條 `insertEvent`，合計 ~4000 次 single-row execute。

`ExecutorType.BATCH`（MyBatis batch executor）在 ShardingSphere
5.5.1 跟這兩張分片表 `journal_line_0..3` /
`projection_event_log_0..3` 撞 — 報 `TableNotFoundException` —
所以不能簡單打開。

**新設計（commit `e2ab551`，2026-06-06）**：50ms micro-batch。

```
Kafka consumer thread
        │ (enqueue, non-blocking)
        ▼
JournalFlushBuffer.shardQueues[0..3]  ──  ConcurrentLinkedQueue<PendingRow>
        │ (per-shard, 按 account_account_id.hashCode() % 4 路由)
        ▼
[per-shard flusher thread, 4 個]
   scheduledAtFixedRate(50ms)  OR  triggered on maxBuffer=4000
        │ (drain, 1 session, 1 transaction)
        ▼
Multi-row INSERT per shard:
  INSERT IGNORE INTO journal_line(...)         VALUES (?,?,...), (?,?,...), ...;
  INSERT IGNORE INTO projection_event_log(...) VALUES (?,...,...), (?,...,...), ...;
```

關鍵設計點：

1. **每 shard 1 個 flusher thread**。互不搶資源，4 shard 並行寫，
   寫入吞吐量 ~4×。
2. **Multi-row VALUES 用 ShardingSphere 邏輯表名**。MyBatis mapper 寫
   `journal_line` / `projection_event_log`（不加 `_${shard}`），讓
   ShardingSphere binder 按每行的 `account_account_id` 路由到
   `journal_line_${shardIndex}`。MyBatis 的 `<foreach>` 必須用
   `<script>` 包裹才生效。
3. **account / journal PK 仍單條寫**。它們不在分片表內，cache 命中
   後無 DB round-trip，不需 batch。
4. **Balance upsert 維持現狀**（`ConflationQueue` + per-worker
   `ExecutorType.BATCH`，單表無 SS 衝突）。

**Tuning knobs**（`application.yml`）：

| Property | Default | 說明 |
|---|---|---|
| `ledger.projection.journal.flush-interval-ms` | `50` | 每 shard flusher 排程週期 |
| `ledger.projection.journal.max-buffer` | `4000` | 任一 shard 滿就立即觸發 flush |
| `ledger.projection.balance.workers` | `2` | balance worker pool 大小（建構子注入，避免 field-injection 時序陷阱） |

**Prometheus 指標**（新增）：

| Metric | 說明 |
|---|---|
| `ledger_projection_journal_buffer_depth` | 4 shard queue 合計待寫入條數 |
| `ledger_projection_journal_flush_batches` | 累計 flush 次數 |
| `ledger_projection_journal_flush_rows` | 累計 multi-row INSERT 條數 |

**測試結果**（`./scripts/test-cycle.sh --vus 10 --duration 2m`，重複 3 次）：

| Metric | 前 | 後 |
|---|---|---|
| Projection 寫入速率 | ~50/s | ~430/s |
| `events_processed` / `journal_flush_rows` | drift | 完全相等 |
| k6 send TPS | ~100 | ~106 |
| MySQL recon（all-account × 2 ccy） | 182/204, 22 lag_warn | 204/204, 0 lag_warn |

**已知 trade-off**：

- Kafka consumer 結束後最多 **50ms** 內可見性延遲（async write）。
  對 projection 而言可接受；`Raft state machine` 不受影響（它是
  source of truth）。
- 單 shard 內仍保持插入順序，跨 shard 不保證（已透過 `accountSeq`
  排序，consumer side 處理）。

---

## 5. 部署

### 5.1 Docker Compose

```yaml
projection:
  build:
    context: .
    dockerfile: Dockerfile.projection
  container_name: ledger-projection
  ports:
    - "8089:8089"
  environment:
    SPRING_DATASOURCE_URL: jdbc:mysql://ledger-mysql:3306/ledger_view
    SPRING_DATASOURCE_USERNAME: ledger
    SPRING_DATASOURCE_PASSWORD: ledger123
    KAFKA_BOOTSTRAP_SERVERS: ledger-kafka:9092
  depends_on:
    mysql:
      condition: service_healthy
    kafka:
      condition: service_started
```

### 5.2 資源配置

| 參數 | 值 | 說明 |
|---|---|---|
| JVM Heap | 1GB | 純 Kafka → MySQL 映射，不需大量記憶體 |
| CPU | 1 core | 輕量級消費者 |
| 實例數 | 1–64 | 按需擴展（取決於 partition 數和吞吐需求） |

---

## 6. 監控

| Metric | 說明 |
|---|---|
| `kafka_consumer_records_consumed_total` | 已消費記錄數 |
| `kafka_consumer_records_lag` | Consumer lag |
| `mysql_insert_latency_seconds` | MySQL 寫入延遲 |
| `projection_errors_total` | 投影失敗計數 |

---

## 7. 驗收標準

| # | 條件 | 測試方式 |
|---|---|---|
| AC-01 | Kafka 事件到達後，1 秒內出現在 MySQL journal 表 | 功能測試 |
| AC-02 | 重複消費同一 event（相同 journalId），MySQL 無重複記錄 | 冪等測試 |
| AC-03 | Projection 崩潰重啟後，從上次 committed offset 繼續消費，無數據丟失 | 故障恢復測試 |
| AC-04 | 多個 Projection 實例共享同一 consumer group，partition 均勻分配 | 擴展測試 |
| AC-05 | MySQL 不可用時，Projection 不崩潰，暫停消費等待 MySQL 恢復 | 容錯測試 |
| AC-06 | AccountCreatedEvent 到達後，account 表與 account_balance 初始化記錄正確寫入 | 功能測試 |
| AC-07 | 重複消費 AccountCreatedEvent（相同 accountId），status 與 display_name 更新但不產生重複記錄 | 冪等測試 |
| AC-08 | account_balance 的 frozen_amount、locked_amount 在 BalanceChangeEvent 投影後保持不變 | 一致性測試 |

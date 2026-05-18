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
- **僅投影必要數據**：journal + journal_line；balance 由 in-memory StateMachine 提供

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
    balance-change: ledger.balance.change.v1
```

### 3.2 Consumer Group 策略

- **Group ID**: `ledger-projection`（多實例共享）
- **Partition 分配**: 64 partitions，支援最多 64 個 Projection 實例水平擴展
- **順序保證**: 同一個 `accountId:balanceType:currency` 的事件必在同一 partition（按 partition key 路由），保證順序投影

---

## 4. 投影邏輯

### 4.1 BalanceChangeEvent → MySQL

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
   - journal_id, journal_type, request_id, business_event_ref,
     value_date, status, created_at

2. INSERT INTO journal_line (idempotent: ON DUPLICATE KEY IGNORE)
   - journal_line_id, journal_id, account_id, balance_type, currency,
     entry_type, amount, balance_before, balance_after, created_at

3. Log projection status (DEBUG level)
```

### 4.2 冪等性保證

```sql
-- journal 表以 journal_id 為 PRIMARY KEY
-- 重複消費同一事件時，INSERT ... ON DUPLICATE KEY 直接跳過，不拋出異常
INSERT INTO journal (...) VALUES (...);

-- journal_line 表以 journal_line_id 為 PRIMARY KEY
-- 同樣 idempotent
INSERT INTO journal_line (...) VALUES (...);
```

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

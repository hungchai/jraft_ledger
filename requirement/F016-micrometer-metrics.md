# F-016 Micrometer Metrics & Observability — 功能需求規格

**文件版本**: v0.1  
**功能**: F-016 Micrometer Metrics & Observability  
**系統**: Next-Gen Internal Ledger Platform  
**狀態**: Draft for Review  
**依賴**: ADR-001, F-002, F-003, F-004, F-005, F-008, F-012, NFR-9  
**變更摘要**: 首次定義 Ledger Platform 全部 Micrometer 指標清單、Prometheus 抓取保型、Grafana Alert Rule 與 Dashboard Panel 規格。涵蓋 ledger-core、ledger-restful、ledger-projection 三模組。

---

## 1. 功能概述

本規格定義 Ledger Platform 所有透過 Micrometer + Prometheus 暴露的指標（Metrics），包含：

- **業務指標**：Posting、Reversal、Adjustment、Balance Query 的延遲與拒絕率
- **基礎設施指標**：Raft Leader 狀態、Account Queue 深度、Outbox 積壓、Projection Lag
- **JVM 指標**：GC Pause、Memory、Threads、CPU
- **Spring Boot 預設指標**：HTTP Server Requests、System Metrics

所有 `ledger.` 前綴指標啟用 **Percentile Histogram**（最小 1μs，最大 10s），供 Prometheus `histogram_quantile()` 計算 P50/P95/P99。

---

## 2. 指標總覽

### 2.1 Timer（延遲分佈 Histogram）

| 指標名稱 | 類型 | Tags | 說明 | 來源 |
|---|---|---|---|---|
| `ledger.posting.duration` | Timer | `outcome` (COMPLETED/REJECTED/ERROR), `businessEventType` | Posting API 端到端處理時間（nanoseconds） | `PostingController` |
| `ledger.reversal.duration` | Timer | `outcome` (COMPLETED/REJECTED/ERROR) | Reversal API 端到端處理時間 | `ReversalController` |
| `ledger.adjustment.duration` | Timer | `outcome` (COMPLETED/REJECTED/ERROR), `operation` (create-draft/approve) | Adjustment API 端到端處理時間 | `AdjustmentController` |
| `ledger.balance.query.duration` | Timer | `queryType` (live/live-position/asof/batch) | Balance Query 處理時間 | `BalanceQueryController` |

**Histogram 配置**（`MeterRegistryCustomizer`）：
- `percentilesHistogram(true)` — 啟用 Prometheus bucket 輸出
- `minimumExpectedValue(1.0)` — 最小預期值 1.0（單位依 Timer 而定，實際為 nanoseconds 記錄後轉 seconds）
- `maximumExpectedValue(10000.0)` — 最大預期值 10,000

**SLI Bucket 設計**（供 SLO 計算）：
- `ledger_posting_duration_seconds_bucket{le="0.003"}` — P95 ≤ 3ms 判定閾值
- `ledger_balance_query_duration_seconds_bucket{le="0.002"}` — P95 ≤ 2ms 判定閾值

---

### 2.2 Counter（累計計數器）

| 指標名稱 | 類型 | Tags | 說明 | 來源 |
|---|---|---|---|---|
| `ledger.posting.rejected.count` | Counter | `errorCode` (INSUFFICIENT_BALANCE/CREDIT_EXCEEDS_LIMIT/NOT_LEADER/...) | Posting 被拒絕次數 | `PostingController` |
| `ledger.reconciliation.l1.unbalanced.total` | Counter | — | L1 對帳發現的借貸不平衡 Journal 總數 | `ReconciliationService` |

---

### 2.3 Gauge（瞬時值）— ledger-core / ledger-restful

| 指標名稱 | 類型 | Tags | 說明 | 來源 |
|---|---|---|---|---|
| `ledger.outbox.pending` | Gauge | — | OutboxStore (CF_OUTBOX) 中待發布事件數量 | `LedgerConfig` → `AsyncOutboxPublisher` |
| `ledger.outbox.published` | Gauge | — | 累計已成功發布至 Kafka 的事件數 | `LedgerConfig` |
| `ledger.outbox.failed` | Gauge | — | 累計發布失敗的事件數 | `LedgerConfig` |
| `ledger.outbox.last_scan_pending` | Gauge | — | 最近一次掃描發現的 pending 事件數 | `LedgerConfig` |
| `ledger.outbox.last_scan_duration_ms` | Gauge | — | 最近一次掃描耗時（毫秒） | `LedgerConfig` |
| `ledger.raft.is_leader` | Gauge | `node_id` | 1 = Leader, 0 = Follower | `LedgerConfig` → `RaftNodeManager` |
| `ledger.raft.last_applied_index` | Gauge | `node_id` | Raft State Machine 最後已應用 Log Index（單調遞增） | `LedgerConfig` |
| `ledger.account.queue.depth` | Gauge | `accountId` | 指定帳戶的 Account Queue 待處理深度（預設監控 4 個 hotspot） | `LedgerConfig` → `AccountQueueManager` |
| `ledger.account.queue.active` | Gauge | — | 當前活躍（非空）Account Queue 數量 | `LedgerConfig` |

**Hotspot Account 監控清單**（預設）：
- `STRESS-HOT-CO-001`
- `COMPANY_FX_ACC`
- `NOSTRO_USD`
- `SUSPENSE_USD`

---

### 2.4 Gauge（瞬時值）— ledger-projection

| 指標名稱 | 類型 | Tags | 說明 | 來源 |
|---|---|---|---|---|
| `ledger.projection.seconds.since.last.event` | Gauge | — | 距上次成功處理 Projection 事件的秒數 | `ProjectionWriter` |
| `ledger.projection.events.processed` | Gauge | — | 累計已處理的 Projection 事件數 | `ProjectionWriter` |
| `ledger.projection.balance.queue.depth` | Gauge | — | ConflationQueue 中待合併的 Balance Update 數量 | `ProjectionWriter` |
| `ledger.projection.balance.writes` | Gauge | — | 累計寫入 MySQL 的 Balance SQL 次數（合併後） | `ProjectionWriter` |

---

### 2.5 JVM & Spring Boot 預設指標

| 指標名稱 | 類型 | 說明 | 用途 |
|---|---|---|---|
| `jvm_gc_pause_seconds_max` | Gauge | 最近 GC 最大暫停時間 | Alert: GC pause > 10ms |
| `jvm_memory_used_bytes` | Gauge | JVM Heap / Non-Heap 已用記憶體 | Dashboard / Capacity Planning |
| `jvm_threads_live` | Gauge | 當前存活執行緒數 | Dashboard |
| `system_cpu_usage` | Gauge | 系統 CPU 使用率 | Dashboard |
| `http_server_requests_seconds_bucket` | Histogram | HTTP 請求延遲分佈 | 預設 Spring Boot Actuator，補充 `ledger.*` 業務指標 |
| `process_uptime_seconds` | Gauge | 進程運行時間 | Dashboard / Restart Detection |

---

## 3. Prometheus 抓取保型

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: 'ledger-nodes'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets:
          - 'ledger-node-1:8080'
          - 'ledger-node-2:8080'
          - 'ledger-node-3:8080'
        labels:
          app: 'ledger'
          component: 'state-machine'

  - job_name: 'projection'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['ledger-projection:8089']
        labels:
          app: 'ledger'
          component: 'projection'
```

**暴露端點**：所有 ledger 節點（8081-8083）與 projection（8089）均暴露 `/actuator/prometheus`。  
**Spring Boot 配置**（`application.yml`）：
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,metrics,info
  metrics:
    export:
      prometheus:
        enabled: true
```

---

## 4. Alert Rules（Grafana Alerting）

### 4.1 Critical（PagerDuty）

| Alert UID | 條件 | 持續時間 | 嚴重度 | Prometheus Expr |
|---|---|---|---|---|
| `posting-latency-high` | Posting P95 > 3ms | 2m | critical | `histogram_quantile(0.95, rate(ledger_posting_duration_seconds_bucket[2m])) > 0.003` |
| `raft-leader-election` | Leader 切換 | — | critical | `changes(ledger_raft_is_leader[5m]) > 0` |
| `journal-unbalanced` | L1 對帳發現不平衡 Journal | 1m | critical | `ledger_reconciliation_l1_unbalanced_total > 0` |
| `projection-lag-critical` | Projection lag > 30s | 5m | critical | `ledger_projection_seconds_since_last_event > 30` |

### 4.2 Warning（Slack / Email）

| Alert UID | 條件 | 持續時間 | 嚴重度 | Prometheus Expr |
|---|---|---|---|---|
| `queue-depth-high` | 單一 Account Queue depth > 500 | — | warning | `ledger_account_queue_depth > 500` |
| `gc-pause-high` | JVM GC pause > 10ms | 1m | warning | `max(jvm_gc_pause_seconds_max) > 0.01` |
| `outbox-backlog` | Outbox pending > 10,000 | 2m | warning | `ledger_outbox_pending > 10000` |
| `projection-lag-high` | Projection lag > 10s | 2m | warning | `ledger_projection_seconds_since_last_event > 10` |
| `rejection-rate-high` | Posting rejection rate > 10/min | 1m | warning | `rate(ledger_posting_rejected_count[5m]) > 10` |

---

## 5. Grafana Dashboard 規格

### 5.1 Dashboard: Ledger Overview（ID: ledger-overview）

| Row | Panel | 指標 | 類型 |
|---|---|---|---|
| Posting | Posting P95/P99 Latency | `histogram_quantile(0.95, rate(ledger_posting_duration_seconds_bucket[5m]))` | Time Series |
| Posting | Posting Rejection Rate | `rate(ledger_posting_rejected_count[5m])` | Time Series |
| Posting | Posting Outcome Distribution | `ledger_posting_duration` by `outcome` | Bar Gauge |
| Balance | Balance Query P95 Latency | `histogram_quantile(0.95, rate(ledger_balance_query_duration_seconds_bucket[5m]))` | Time Series |
| Raft | Leader Status | `ledger_raft_is_leader` | Stat (0/1) |
| Raft | Last Applied Index | `ledger_raft_last_applied_index` | Time Series |
| Queue | Hotspot Queue Depth | `ledger_account_queue_depth` by `accountId` | Time Series |
| Queue | Active Queues | `ledger_account_queue_active` | Time Series |
| Outbox | Pending Events | `ledger_outbox_pending` | Time Series |
| Outbox | Publish Rate | `rate(ledger_outbox_published[5m])` | Time Series |
| Projection | Projection Lag | `ledger_projection_seconds_since_last_event` | Time Series |
| Projection | Events Processed Rate | `rate(ledger_projection_events_processed[5m])` | Time Series |
| JVM | GC Pause Max | `jvm_gc_pause_seconds_max` | Time Series |
| JVM | Heap Used | `jvm_memory_used_bytes{area="heap"}` | Time Series |

### 5.2 Dashboard: Ledger Reconciliation（ID: ledger-reconciliation）

| Panel | 指標 | 類型 |
|---|---|---|
| L1 Unbalanced Journals | `ledger_reconciliation_l1_unbalanced_total` | Stat |

---

## 6. 驗收標準（AC）

| AC | 描述 | 驗證方式 | TC 編號 |
|---|---|---|---|
| AC-01 | `/actuator/prometheus` 返回所有 `ledger.` 前綴指標 | 整合測試 | TC-F015-01 |
| AC-02 | `ledger.posting.duration` 包含 `outcome` + `businessEventType` 兩個 tag | 整合測試 | TC-F015-02 |
| AC-03 | `ledger.balance.query.duration` 包含 `queryType` (live/live-position/asof/batch) | 整合測試 | TC-F015-03 |
| AC-04 | Hotspot account gauges (`ledger.account.queue.depth`) 預設註冊 4 個帳戶 | 整合測試 | TC-F015-04 |
| AC-05 | Projection gauges 在 projection 節點獨立暴露 | 整合測試 | TC-F015-05 |
| AC-06 | Alert Rule `posting-latency-high` 在 P95 > 3ms 持續 2min 觸發 | Alert 測試 | TC-F015-06 |
| AC-07 | Alert Rule `raft-leader-election` 在 leader 切換時立即觸發 | Alert 測試 | TC-F015-07 |
| AC-08 | Histogram bucket 包含 `le="0.003"` 供 P95 ≤ 3ms SLO 計算 | 指標輸出檢查 | TC-F015-08 |
| AC-09 | `ledger.outbox.pending` 在 Outbox 積壓時 > 0 | 整合測試 | TC-F015-09 |
| AC-10 | `ledger.reconciliation.l1.unbalanced.total` 在 L1 發現不平衡時 +1 | 整合測試 | TC-F015-10 |

---

## 7. 錯誤碼

本功能無新增業務錯誤碼。指標暴露失敗由 Spring Boot Actuator 標準機制處理（HTTP 500）。

---

## 8. 性能目標

| 指標 | 目標 | 說明 |
|---|---|---|
| Prometheus Scrape P95 | ≤ 100ms | `/actuator/prometheus` 回應時間 |
| Metrics 記錄開銷 | ≤ 1μs | Timer.record() 單次呼叫額外延遲 |
| Gauge 讀取開銷 | ≤ 100ns | Gauge 值讀取額外延遲 |

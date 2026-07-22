# F-016 Micrometer Metrics & Observability — Functional Requirements Specification

**Document Version**: v0.1  
**Feature**: F-016 Micrometer Metrics & Observability  
**System**: Next-Gen Internal Ledger Platform  
**Status**: Draft for Review  
**Dependencies**: ADR-001, F-002, F-003, F-004, F-005, F-008, F-012, NFR-9  
**Change Summary**: First definition of all Ledger Platform Micrometer metrics, Prometheus scrape configuration, Grafana Alert Rules, and Dashboard Panel specifications. Covers ledger-core, ledger-restful, and ledger-projection modules.

---

## 1. Overview

This specification defines all metrics exposed by the Ledger Platform via Micrometer + Prometheus, including:

- **Business metrics**: Latency and rejection rates for Posting, Reversal, Adjustment, and Balance Query
- **Infrastructure metrics**: Raft leader status, Account Queue depth, Outbox backlog, Projection lag
- **JVM metrics**: GC pause, memory, threads, CPU
- **Spring Boot default metrics**: HTTP server requests, system metrics

All `ledger.` prefixed metrics enable **Percentile Histogram** (min 1μs, max 10s) for Prometheus `histogram_quantile()` calculations of P50/P95/P99.

---

## 2. Metric Inventory

### 2.1 Timers (Latency Distribution Histograms)

| Metric Name | Type | Tags | Description | Source |
|---|---|---|---|---|
| `ledger.posting.duration` | Timer | `outcome` (COMPLETED/REJECTED/ERROR), `businessEventType` | End-to-end Posting API processing time (nanoseconds) | `PostingController` |
| `ledger.reversal.duration` | Timer | `outcome` (COMPLETED/REJECTED/ERROR) | End-to-end Reversal API processing time | `ReversalController` |
| `ledger.adjustment.duration` | Timer | `outcome` (COMPLETED/REJECTED/ERROR), `operation` (create-draft/approve) | End-to-end Adjustment API processing time | `AdjustmentController` |
| `ledger.balance.query.duration` | Timer | `queryType` (live/live-position/asof/batch) | Balance Query processing time | `BalanceQueryController` |

**Histogram Configuration** (`MeterRegistryCustomizer`):
- `percentilesHistogram(true)` — Enable Prometheus bucket output
- `minimumExpectedValue(1.0)` — Minimum expected value 1.0 (unit depends on Timer; actual recording is nanoseconds, converted to seconds)
- `maximumExpectedValue(10000.0)` — Maximum expected value 10,000

**SLI Bucket Design** (for SLO calculations):
- `ledger_posting_duration_seconds_bucket{le="0.003"}` — P95 ≤ 3ms threshold
- `ledger_balance_query_duration_seconds_bucket{le="0.002"}` — P95 ≤ 2ms threshold

---

### 2.2 Counters (Cumulative Counters)

| Metric Name | Type | Tags | Description | Source |
|---|---|---|---|---|
| `ledger.posting.rejected.count` | Counter | `errorCode` (INSUFFICIENT_BALANCE/CREDIT_EXCEEDS_LIMIT/NOT_LEADER/...) | Number of rejected Posting requests | `PostingController` |
| `ledger.reconciliation.l1.unbalanced.total` | Counter | — | Total number of debit-credit unbalanced journals found by L1 reconciliation | `ReconciliationService` |

---

### 2.3 Gauges (Instant Values) — ledger-core / ledger-restful

| Metric Name | Type | Tags | Description | Source |
|---|---|---|---|---|
| `ledger.outbox.pending` | Gauge | — | Number of events pending in OutboxStore (CF_OUTBOX) | `LedgerConfig` → `AsyncOutboxPublisher` |
| `ledger.outbox.published` | Gauge | — | Cumulative count of events successfully published to Kafka | `LedgerConfig` |
| `ledger.outbox.failed` | Gauge | — | Cumulative count of events that failed to publish | `LedgerConfig` |
| `ledger.outbox.last_scan_pending` | Gauge | — | Number of pending events found in the last outbox scan | `LedgerConfig` |
| `ledger.outbox.last_scan_duration_ms` | Gauge | — | Duration of the last outbox scan in milliseconds | `LedgerConfig` |
| `ledger.raft.is_leader` | Gauge | `node_id` | 1 = Leader, 0 = Follower | `LedgerConfig` → `RaftNodeManager` |
| `ledger.raft.last_applied_index` | Gauge | `node_id` | Raft State Machine last applied log index (monotonic) | `LedgerConfig` |
| `ledger.account.queue.depth` | Gauge | `accountId` | Pending command queue depth for the specified account (4 hotspots monitored by default) | `LedgerConfig` → `AccountQueueManager` |
| `ledger.account.queue.active` | Gauge | — | Number of currently active (non-empty) account queues | `LedgerConfig` |

**Hotspot Account Monitoring List** (default):
- `STRESS-HOT-CO-001`
- `COMPANY_FX_ACC`
- `NOSTRO_USD`
- `SUSPENSE_USD`

---

### 2.4 Gauges (Instant Values) — ledger-projection

| Metric Name | Type | Tags | Description | Source |
|---|---|---|---|---|
| `ledger.projection.seconds.since.last.event` | Gauge | — | Seconds since the last projection event was successfully processed | `ProjectionWriter` |
| `ledger.projection.events.processed` | Gauge | — | Cumulative count of projection events processed | `ProjectionWriter` |
| `ledger.projection.balance.queue.depth` | Gauge | — | Number of pending conflated balance updates in ConflationQueue | `ProjectionWriter` |
| `ledger.projection.balance.writes` | Gauge | — | Cumulative count of balance SQL writes to MySQL (after conflation) | `ProjectionWriter` |

---

### 2.5 JVM & Spring Boot Default Metrics

| Metric Name | Type | Description | Purpose |
|---|---|---|---|
| `jvm_gc_pause_seconds_max` | Gauge | Maximum GC pause duration | Alert: GC pause > 10ms |
| `jvm_memory_used_bytes` | Gauge | JVM Heap / Non-Heap memory used | Dashboard / Capacity Planning |
| `jvm_threads_live` | Gauge | Current number of live threads | Dashboard |
| `system_cpu_usage` | Gauge | System CPU utilization | Dashboard |
| `http_server_requests_seconds_bucket` | Histogram | HTTP request latency distribution | Default Spring Boot Actuator; supplements `ledger.*` business metrics |
| `process_uptime_seconds` | Gauge | Process uptime in seconds | Dashboard / Restart Detection |

---

## 3. Prometheus Scrape Configuration

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

**Expose Endpoint**: All ledger nodes (8081–8083) and projection (8089) expose `/actuator/prometheus`.  
**Spring Boot Configuration** (`application.yml`):
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

## 4. Alert Rules (Grafana Alerting)

### 4.1 Critical (PagerDuty)

| Alert UID | Condition | Duration | Severity | Prometheus Expr |
|---|---|---|---|---|
| `posting-latency-high` | Posting P95 > 3ms | 2m | critical | `histogram_quantile(0.95, rate(ledger_posting_duration_seconds_bucket[2m])) > 0.003` |
| `raft-leader-election` | Leader change detected | — | critical | `changes(ledger_raft_is_leader[5m]) > 0` |
| `journal-unbalanced` | L1 reconciliation found unbalanced journal | 1m | critical | `ledger_reconciliation_l1_unbalanced_total > 0` |
| `projection-lag-critical` | Projection lag > 30s | 5m | critical | `ledger_projection_seconds_since_last_event > 30` |

### 4.2 Warning (Slack / Email)

| Alert UID | Condition | Duration | Severity | Prometheus Expr |
|---|---|---|---|---|
| `queue-depth-high` | Single Account Queue depth > 500 | — | warning | `ledger_account_queue_depth > 500` |
| `gc-pause-high` | JVM GC pause > 10ms | 1m | warning | `max(jvm_gc_pause_seconds_max) > 0.01` |
| `outbox-backlog` | Outbox pending > 10,000 | 2m | warning | `ledger_outbox_pending > 10000` |
| `projection-lag-high` | Projection lag > 10s | 2m | warning | `ledger_projection_seconds_since_last_event > 10` |
| `rejection-rate-high` | Posting rejection rate > 10/min | 1m | warning | `rate(ledger_posting_rejected_count[5m]) > 10` |

---

## 5. Grafana Dashboard Specifications

### 5.1 Dashboard: Ledger — Main (ID: ledger-main)

| Row | Panel | Metric | Type |
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

### 5.2 Dashboard: Ledger Reconciliation (ID: ledger-reconciliation)

| Panel | Metric | Type |
|---|---|---|
| L1 Unbalanced Journals | `ledger_reconciliation_l1_unbalanced_total` | Stat |

---

## 6. Acceptance Criteria (AC)

| AC | Description | Verification | TC ID |
|---|---|---|---|
| AC-01 | `/actuator/prometheus` returns all `ledger.` prefixed metrics | Integration Test | TC-F015-01 |
| AC-02 | `ledger.posting.duration` includes `outcome` + `businessEventType` tags | Integration Test | TC-F015-02 |
| AC-03 | `ledger.balance.query.duration` includes `queryType` (live/live-position/asof/batch) | Integration Test | TC-F015-03 |
| AC-04 | Hotspot account gauges (`ledger.account.queue.depth`) register 4 accounts by default | Integration Test | TC-F015-04 |
| AC-05 | Projection gauges are independently exposed on the projection node | Integration Test | TC-F015-05 |
| AC-06 | Alert Rule `posting-latency-high` fires when P95 > 3ms sustained for 2min | Alert Test | TC-F015-06 |
| AC-07 | Alert Rule `raft-leader-election` fires immediately on leader change | Alert Test | TC-F015-07 |
| AC-08 | Histogram bucket includes `le="0.003"` for P95 ≤ 3ms SLO calculation | Metric Output Check | TC-F015-08 |
| AC-09 | `ledger.outbox.pending` > 0 when outbox is backlogged | Integration Test | TC-F015-09 |
| AC-10 | `ledger.reconciliation.l1.unbalanced.total` increments by 1 when L1 finds an imbalance | Integration Test | TC-F015-10 |

---

## 7. Error Codes

No new business error codes are introduced by this feature. Metric exposure failures are handled by standard Spring Boot Actuator mechanisms (HTTP 500).

---

## 8. Performance Targets

| Metric | Target | Description |
|---|---|---|
| Prometheus Scrape P95 | ≤ 100ms | `/actuator/prometheus` response time |
| Metrics Recording Overhead | ≤ 1μs | Per-call overhead of `Timer.record()` |
| Gauge Read Overhead | ≤ 100ns | Per-read overhead of Gauge value retrieval |

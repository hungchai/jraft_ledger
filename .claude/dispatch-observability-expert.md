# Dispatch: observability-expert

**From**: ledger-orchestrator
**Task**: Update Grafana dashboard + create alert rules
**Agent**: observability-expert (see `.claude/agents/observability-expert.md`)

## Context

### Dashboard file
`/Users/tomma/GIT/jraft_ledger/grafana/provisioning/dashboards/ledger-overview.json`

### Current panels (8)
1. Posting P95 Latency (gauge)
2. Balance Query P95 Live (gauge)
3. Raft Leader Status (stat)
4. Account Queue Depth Top5 (bargauge)
5. GC Pause Max (gauge)
6. Posting Throughput (timeseries)
7. Raft Last Applied Index (timeseries)
8. JVM Heap Usage (timeseries)

### Metrics already instrumented in Java code

**Timers** (histograms):
- `ledger.posting.duration` — tags: outcome, balanceType
- `ledger.reversal.duration` — tags: outcome
- `ledger.balance.query.duration` — tags: queryType (live/asof/batch)
- `ledger.adjustment.duration` — tags: outcome, stage

**Counters**:
- `ledger.posting.rejected.count` — tags: errorCode

**Gauges**:
- `ledger.account.queue.depth` — tags: accountId
- `ledger.account.queue.active`
- `ledger.raft.is_leader` — tags: node_id
- `ledger.raft.last_applied_index` — tags: node_id
- `ledger.outbox.pending`
- `ledger.outbox.published`
- `ledger.outbox.failed`
- `ledger.outbox.last_scan_pending`
- `ledger.outbox.last_scan_duration_ms`

### What's missing
- No `ledger.reconciliation.*` metrics yet (future)
- No `ledger.eod.*` metrics yet (future)
- No `ledger.kafka.*` lag gauges yet (future)
- Dashboard has no variables (node selector, balanceType selector)
- No alert rules directory exists
- Panels lack `description` fields with NFR target context

### Config files
- Datasource: `grafana/provisioning/datasources/datasource.yml` (Prometheus @ http://prometheus:9090)
- Dashboard provider: `grafana/provisioning/dashboards/dashboard-provider.yml` (auto-loads from dir)
- Docker-compose: Prometheus on port 9090, Grafana on 3000 (admin/admin123)
- Prometheus scrape: already configured for 3 nodes + projection (prometheus.yml)

## Requirements

### 1. Update dashboard to 15 panels

Add these panels (position wisely in 24-column grid):

| # | Title | Type | PromQL | Unit | NFR |
|---|-------|------|--------|------|-----|
| 1 | Posting P95 Latency | gauge | `histogram_quantile(0.95, rate(ledger_posting_duration_seconds_bucket[5m])) * 1000` | ms | ≤3ms |
| 2 | Posting P50 Latency | stat | `histogram_quantile(0.50, rate(ledger_posting_duration_seconds_bucket[5m])) * 1000` | ms | — |
| 3 | Balance Query P95 (Live) | gauge | `histogram_quantile(0.95, rate(ledger_balance_query_duration_seconds_bucket{queryType="live"}[5m])) * 1000` | ms | ≤2ms |
| 4 | Balance Query P95 (As-of) | gauge | `histogram_quantile(0.95, rate(ledger_balance_query_duration_seconds_bucket{queryType="asof"}[5m])) * 1000` | ms | ≤30ms |
| 5 | Raft Leader Status | stat | `ledger_raft_is_leader` | bool | 1 leader |
| 6 | Account Queue Depth (Top 5) | bargauge | `topk(5, ledger_account_queue_depth)` | short | <500 |
| 7 | GC Pause Max | gauge | `max(jvm_gc_pause_seconds_max) * 1000` | ms | <5ms |
| 8 | Posting Throughput (TPS) | timeseries | `rate(ledger_posting_duration_seconds_count[1m])` | ops/s | ≥10k |
| 9 | Raft Last Applied Index | timeseries | `ledger_raft_last_applied_index` | short | — |
| 10 | Outbox Pending Events | timeseries | `ledger_outbox_pending` | short | <1000 |
| 11 | Posting Rejection Rate | timeseries | `rate(ledger_posting_rejected_count[1m])` | ops/s | — |
| 12 | Reversal Duration P95 | gauge | `histogram_quantile(0.95, rate(ledger_reversal_duration_seconds_bucket[5m])) * 1000` | ms | — |
| 13 | Adjustment Duration P95 | gauge | `histogram_quantile(0.95, rate(ledger_adjustment_duration_seconds_bucket[5m])) * 1000` | ms | — |
| 14 | Active Accounts | stat | `ledger_account_queue_active` | short | — |
| 15 | JVM Heap Usage | timeseries | `jvm_memory_used_bytes{area="heap"}` / `jvm_memory_max_bytes{area="heap"}` | bytes | — |

Note: Panel #4 (Balance Query As-of) will show 0 until as-of queries are used in production — keep it as placeholder.
Panel #10 uses `ledger_outbox_pending` gauge (already exposed).
Panels #12, #13 use histograms already in Java code.

### 2. Add dashboard variables

```json
"templating": {
  "list": [
    {
      "name": "node",
      "type": "query",
      "datasource": "Prometheus",
      "query": "label_values(ledger_raft_is_leader, node_id)",
      "multi": true,
      "includeAll": true
    },
    {
      "name": "balanceType",
      "type": "query",
      "query": "label_values(ledger_posting_duration_seconds_count, balanceType)",
      "multi": true,
      "includeAll": true
    }
  ]
}
```

### 3. Add `description` to EVERY panel

Each panel must have a `description` field explaining:
- What the metric measures
- NFR target value (clearly stated)
- What values/colors mean

### 4. Create alert rules file

Create `/Users/tomma/GIT/jraft_ledger/grafana/provisioning/alerting/alert_rules.yml`

Include these alerts:

**Critical (PagerDuty)**:
- `PostingLatencyHigh`: P95 > 3ms for 2min
- `OverdrawnAlert`: TRADEAHEADBALANCE < -500000
- `RaftLeaderElection`: changes(ledger_raft_is_leader[5m]) > 0
- `ReconCasesOpen`: ledger_reconciliation_cases_open > 0 for 5min (placeholder)
- `JournalUnbalanced`: ledger_reconciliation_l1_unbalanced_total > 0 (placeholder)

**Warning (Slack)**:
- `QueueDepthHigh`: ledger_account_queue_depth > 500
- `GcPauseHigh`: max(jvm_gc_pause_seconds_max) > 0.01 (10ms)
- `OutboxBacklog`: ledger_outbox_pending > 10000

### 5. Update `docs/orchestrator-status.md`

Append entry:
```
## [2026-05-29 19:05] Dispatch — observability-expert (dashboard update)
Status: dispatching
Summary: Updating ledger-overview.json from 8→15 panels, adding variables, descriptions, alert rules
Next: Done
```

## Files to modify/create
- `grafana/provisioning/dashboards/ledger-overview.json` — MODIFY
- `grafana/provisioning/alerting/alert_rules.yml` — CREATE (new file)
- `docs/orchestrator-status.md` — APPEND entry when done

## Verify
1. JSON is valid: `python3 -c "import json; json.load(open('...'))"`
2. No hardcoded datasource names — use `"datasource": null`
3. Panel IDs are unique
4. All gridPos x + w ≤ 24
5. docker-compose already mounts `./grafana/provisioning:/etc/grafana/provisioning` — alerting dir auto-loaded

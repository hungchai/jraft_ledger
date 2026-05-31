# Dispatch: observability-expert

**From**: ledger-orchestrator
**Task**: Add Kafka consumer lag Prometheus gauge + alert for projection
**Agent**: observability-expert (see `.claude/agents/observability-expert.md`)
**Dispatched**: 2026-05-30T09:58:15Z

## Context

## Task
Add Kafka consumer lag Prometheus gauge for projection service + alert rule.

## Current State
- Projection consumer reads from Kafka topic `ledger.balance.change.v1` and writes to MySQL
- After k6 stress test, projection lags by ~45% journals (5,605 MySQL vs 10,100 SM)
- No monitoring or alerting for projection lag exists
- Projection service runs on port 8089, exposes `/actuator/prometheus`

## Requirements
1. **Prometheus gauge**: `ledger_projection_kafka_consumer_lag` (Gauge) in ProjectionConsumer
   - Labels: `topic`, `partition`
   - Value: difference between latest Kafka offset and last committed offset
   - Update on each poll cycle

2. **Alert rule** in `grafana/provisioning/alerting/alert_rules.yml`:
   - `ProjectionLagHigh`: lag > 1000 messages for > 2 minutes → WARNING
   - `ProjectionLagCritical`: lag > 5000 messages for > 5 minutes → CRITICAL

3. **Verify**: confirm `/actuator/prometheus` on projection (8089) exposes the metric

## Status

Pending — awaiting agent pickup.

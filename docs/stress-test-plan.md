# Stress Test Plan — Next-Gen Internal Ledger Platform

**Version**: v1.0
**Date**: 2026-05-23
**Owner**: QA Engineering
**Level**: L4 E2E / L5 Chaos (hybrid)
**Gate**: Major Release Candidate

---

## 1. Objectives

| ID | Objective | Success Criteria | Mapped TC |
|---|---|---|---|
| OBJ-01 | Validate Posting P95 ≤ 3 ms under 10,000 TPS peak | P95 ≤ 3 ms, P99 ≤ 10 ms | TC-NFR-01, TC-NFR-02 |
| OBJ-02 | Validate hotspot account (COMPANY_ACC) serialisation | No duplicate payouts, no balance drift | TC-F013-06, TC-F013-07 |
| OBJ-03 | Validate idempotency under retry storm | 1,000 duplicate requestId → 1 Journal | TC-NFR-04, TC-F013-05 |
| OBJ-04 | Validate back-pressure (queue saturation) | HTTP 429 QUEUE_FULL returned when depth > MAX_QUEUE_SIZE | TC-F013-08 |
| OBJ-05 | Validate concurrent same-account withdrawal | No overdraft on allowNegative=false | TC-F002-08, TC-NFR-05 |
| OBJ-06 | Validate balance query latency under write load | P95 ≤ 2 ms (live), ≤ 5 ms (RocksDB warm) | TC-NFR-03 |
| OBJ-07 | Validate Raft durability during sustained load | RPO = 0; no journal loss after node restart | TC-RAFT-* |
| OBJ-08 | Validate GC pause budget | Single GC pause ≤ 5 ms | NFR-13 |

---

## 2. Environment Prerequisites

### 2.1 Cluster Topology (Minimum)

```
3 Raft Voting Nodes  (ledger-1 :8081, ledger-2 :8082, ledger-3 :8083)
1 Learner / Projection (ledger-projection :8089)
1 MySQL 8.4            (:3306)
1 Kafka                (:9092)
1 Prometheus           (:9090)
1 Grafana              (:3000)
```

### 2.2 JVM Flags (Mandatory)

```bash
-XX:+UseZGC
-XX:MaxGCPauseMillis=1
-Xms8g -Xmx8g
```

> G1GC / ParallelGC prohibited. Any GC pause > 5 ms = test failure.

### 2.3 Data Reset Procedure

```bash
# 1. Stop all services
docker compose down -v

# 2. Flush host-mounted stores
rm -rf ./jraft_ledger/node1/rocksdb
rm -rf ./jraft_ledger/node2/rocksdb
rm -rf ./jraft_ledger/node3/rocksdb
rm -rf ./jraft_ledger/mysql

# 3. Restart
docker compose up -d --build

# 4. Wait for healthy cluster
until curl -s http://localhost:8081/health | grep -q "UP"; do sleep 2; done
until curl -s http://localhost:8082/health | grep -q "UP"; do sleep 2; done
until curl -s http://localhost:8083/health | grep -q "UP"; do sleep 2; done
```

---

## 3. Test Data Seeding

### 3.1 Account Matrix

| Account Type | Count | Prefix | Balance Init | allowNegative |
|---|---|---|---|---|
| CLIENT | 9,990 | `STRESS-CLI-` | 1,000 USDT | false |
| COMPANY | 10 | `STRESS-CO-` | 1,000,000 USDT | false |
| HOTSPOT | 1 | `STRESS-HOT-CO-001` | 1,000 BTC + 200M USDT | false |

> **Total**: 10,001 accounts. Creates deterministic test population.

### 3.2 Seeding Validation

- After creation, sample 1 % of accounts and verify `GET /ledger/balances` returns expected initial balance.
- Verify MySQL View Layer `accounts` table row count = 10,001.
- Verify RocksDB `CF_ACCOUNT` key count = 10,001 × balanceTypes.

---

## 4. Test Phases

### Phase 1 — Baseline Warm-Up

**Purpose**: Stabilise JVM JIT, warm RocksDB caches, verify metrics pipeline.

| Parameter | Value |
|---|---|
| Duration | 60 s |
| Load | 100 TPS, single CLIENT ↔ COMPANY pair |
| Assertions | All HTTP 200, P95 < 10 ms, Prometheus scraping OK |

### Phase 2 — High-Frequency RFQ (Hotspot)

**Purpose**: Simulate real RFQ flow where many CLIENT accounts trade against a single COMPANY_ACC.

```
CLIENT_001 ──┐
CLIENT_002 ──┤
...          ├─→ HOTSPOT_COMPANY_ACC
CLIENT_999 ──┘
```

| Parameter | Value |
|---|---|
| Duration | 120 s |
| Concurrent clients | 1,000 |
| Posting per client | 100 RFQ (10,000 total) |
| Amount per RFQ | 1.00 USDT |
| Direction | CLIENT DEBIT 1.00 USDT → HOTSPOT CREDIT 1.00 USDT |
| Assertion | No duplicate journals, HOTSPOT final USDT balance = 200M + 10,000.00 |

**Mapped TC**: TC-F002-09, TC-F013-06, TC-F013-07, TC-NFR-02

### Phase 3 — Deposit / Withdrawal Mix

**Purpose**: Validate balanced posting integrity under mixed direction load.

| Parameter | Value |
|---|---|
| Duration | 120 s |
| Ratio | 60 % deposit (CLIENT credit), 40 % withdrawal (CLIENT debit) |
| Concurrent | 500 |
| Total postings | 20,000 |
| Amount | Random uniform 1.00 – 100.00 USDT |
| Assertion | Sum(all debits) = Sum(all credits) per currency. L1 reconciliation passes. |

**Mapped TC**: TC-F002-01, TC-F007-01

### Phase 4 — Concurrent Same-Account Withdrawal

**Purpose**: Race-condition detection on single account with finite balance.

| Parameter | Value |
|---|---|
| Target account | `STRESS-CLI-MAX-001` seeded with 1,000.00 USDT |
| Concurrent | 1,100 |
| Each withdrawal | 1.00 USDT |
| Expected successes | 1,000 (balance exhausted) |
| Expected rejections | 100 (INSUFFICIENT_BALANCE) |
| Assertion | No negative balance observed. Final balance = 0.00. Exactly 1,000 COMPLETED journals. |

**Mapped TC**: TC-F002-08, TC-NFR-05

### Phase 5 — Idempotency Storm

**Purpose**: Retry avalanche on identical requestId must not create duplicates.

| Parameter | Value |
|---|---|
| Unique requestId count | 100 |
| Retries per requestId | 1,000 |
| Total HTTP calls | 100,000 |
| Concurrent | 500 |
| Assertion | 100 journals total. 99,900 responses return cached result. HOTSPOT balance unchanged. |

**Mapped TC**: TC-NFR-04, TC-F013-05

### Phase 6 — Backpressure / Queue Saturation

**Purpose**: Verify fast-fail when Account Queue depth exceeds `ledger.account-queue.max-size`.

| Parameter | Value |
|---|---|
| Target | Single HOTSPOT account |
| Concurrent | 5,000 (deliberately excessive) |
| Burst duration | 10 s |
| Assertion | HTTP 429 QUEUE_FULL observed. No OOM. Memory returns to baseline after burst. |

**Mapped TC**: TC-F013-08

### Phase 7 — Read / Write Interleave

**Purpose**: Ensure balance query latency remains within NFR under write load.

| Parameter | Value |
|---|---|
| Write load | 1,000 TPS posting (Phase 2 RFQ) |
| Read load | 10,000 QPS balance query (live + as-of) |
| Duration | 60 s |
| Assertion | Balance query P95 ≤ 2 ms (live), ≤ 5 ms (as-of). No stale reads > 1 s. |

**Mapped TC**: TC-NFR-03

### Phase 8 — Raft Leader Failover Under Load

**Purpose**: Validate RTO ≤ 30 s and zero journal loss during leader transition.

| Parameter | Value |
|---|---|
| Background load | 500 TPS continuous posting |
| Failover trigger | `docker stop ledger-1` (current Leader) |
| Observation window | 60 s post-failover |
| Assertion | Election completes ≤ 30 s. No duplicate journals. No missing sequence numbers. All in-flight requests eventually get COMPLETED or REJECTED (no timeout > 5 s). |

**Mapped TC**: TC-RAFT-03, TC-F013-03

### Phase 9 — Memory / GC Stability (Optional Extended)

| Parameter | Value |
|---|---|
| Duration | 600 s (10 min) |
| Load | 5,000 TPS sustained |
| Assertion | Heap usage stable (no monotonic growth). GC pause P99 ≤ 5 ms. No `OutOfMemoryError`. |

---

## 5. Metrics Collection

### 5.1 Prometheus Queries (Execute After Each Phase)

```promql
# Posting latency P95 / P99
histogram_quantile(0.95, rate(ledger_posting_duration_seconds_bucket[2m]))
histogram_quantile(0.99, rate(ledger_posting_duration_seconds_bucket[2m]))

# Posting throughput
rate(ledger_posting_duration_seconds_count[1m])

# Balance query latency
histogram_quantile(0.95, rate(ledger_balance_query_duration_seconds_bucket[2m]))

# Queue depth (sampled)
ledger_state_machine_queue_depth

# GC pause
jvm_gc_pause_seconds_max

# Raft leader status
ledger_raft_leader_election_count
```

### 5.2 Log Correlation

Collect `traceId` for every Phase-4 and Phase-5 request. After test:

```bash
grep "POSTING" /var/log/ledger/app.log | jq -r '
  select(.outcome == "COMPLETED" or .outcome == "REJECTED") |
  {traceId, requestId, journalId, durationMs, outcome, errorCode}
'
```

### 5.3 Reconciliation Checkpoint

After each phase, run:

```bash
curl -X POST http://localhost:8081/ledger/reconciliation/l1
curl -X POST http://localhost:8081/ledger/reconciliation/l2
```

Expected: `status: PASSED`, `openCases: 0`.

---

## 6. Success Criteria (Pass / Fail Gate)

| Gate | Condition | Severity |
|---|---|---|
| G-01 | Posting P95 ≤ 3 ms | BLOCKER |
| G-02 | Posting P99 ≤ 10 ms | BLOCKER |
| G-03 | Hotspot account balance drift = 0 | BLOCKER |
| G-04 | Duplicate journal count = 0 | BLOCKER |
| G-05 | Negative balance on allowNegative=false = 0 | BLOCKER |
| G-06 | Idempotency storm: 100 unique → 100 journals | BLOCKER |
| G-07 | Queue saturation returns 429, no OOM | CRITICAL |
| G-08 | Balance query P95 ≤ 2 ms (live) | CRITICAL |
| G-09 | Leader failover RTO ≤ 30 s | CRITICAL |
| G-10 | GC pause max ≤ 5 ms | CRITICAL |
| G-11 | L1/L2 reconciliation passes after each phase | MAJOR |
| G-12 | No ERROR logs in application log | MAJOR |

---

## 7. Test Artifacts

| Artifact | Location | Description |
|---|---|---|
| Test script | `scripts/stress-test.sh` | Executable bash driver |
| Prometheus snapshot | `prometheus-snapshots/stress-$(date +%Y%m%d-%H%M).tar` | Full TSDB backup |
| Application logs | `logs/ledger-{1,2,3}/` | Structured JSON logs |
| GC logs | `logs/gc-{1,2,3}.log` | ZGC pause details |
| Test report | `docs/stress-test-report-YYYYMMDD.md` | Human-readable summary |

---

## 8. Risk & Mitigation

| Risk | Mitigation |
|---|---|
| Docker Desktop resource limit on 10k accounts | Increase Docker memory to ≥ 16 GB; run on Linux VM if needed |
| `curl` bottleneck in bash script | Use `wrk` / `k6` / `locust` for > 1,000 concurrent; bash script capped at 500 concurrent with background jobs |
| MySQL connection exhaustion | Verify `max_connections ≥ 500`; use connection pool |
| Kafka lag during burst | Monitor `ledger_kafka_publish_lag`; alert if > 1,000 for > 30 s |
| ZGC not enabled | Assert JVM flags at start-up; fail fast if G1GC detected |

---

## 9. Traceability Matrix

| Phase | Requirement | Test Case | Script Section |
|---|---|---|---|
| 1 | NFR-1 | TC-NFR-01 | warm_up() |
| 2 | F-013 §3 | TC-F013-06, TC-F013-07 | phase2_rfq_hotspot() |
| 3 | F-002 | TC-F002-01 | phase3_mixed_posting() |
| 4 | F-002 §2.4 | TC-F002-08, TC-NFR-05 | phase4_same_account_race() |
| 5 | F-013 §2 | TC-NFR-04, TC-F013-05 | phase5_idempotency_storm() |
| 6 | F-013 §3.5 | TC-F013-08 | phase6_backpressure() |
| 7 | NFR-1, NFR-3 | TC-NFR-03 | phase7_read_write_mix() |
| 8 | NFR-3, F-013 §2.5 | TC-F013-03, TC-RAFT-03 | phase8_leader_failover() |
| 9 | NFR-13 | — | phase9_sustained() |

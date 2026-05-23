# Stress Test Execution Report

**Date**: YYYY-MM-DD
**Tester**: 
**Branch / Commit**: 
**Environment**: 

---

## 1. Environment

| Component | Version / Config |
|---|---|
| Ledger nodes | 3× Raft (:8081–8083) |
| JVM | ZGC, -Xms8g -Xmx8g |
| MySQL | 8.0 |
| Kafka | |

## 2. Phases Executed

| Phase | Target | Duration | Requests | OK | Fail | Throughput | P95 | P99 |
|---|---|---|---|---|---|---|---|---|
| 2 RFQ Hotspot | 1,000 clients × 100 | | | | | | | |
| 3 Mix | 20,000 postings | | | | | | | |
| 4 Same-Account | 1,100 concurrent | | | | | | | |
| 5 Idempotency | 100 × 1,000 retries | | | | | | | |
| 6 Backpressure | 5,000 burst | | | | | | | |
| 7 Read/Write | 60 s interleave | | | | | | | |

## 3. Gate Results

| Gate | Criterion | Result | Status |
|---|---|---|---|
| G-01 | Posting P95 ≤ 3 ms | | |
| G-02 | Posting P99 ≤ 10 ms | | |
| G-03 | Hotspot balance drift = 0 | | |
| G-04 | Duplicate journal count = 0 | | |
| G-05 | No negative balance | | |
| G-06 | Idempotency 100 unique → 100 journals | | |
| G-07 | HTTP 429 observed | | |
| G-08 | Balance query P95 ≤ 2 ms | | |

## 4. Prometheus Snapshots

```promql
# P95 posting latency
histogram_quantile(0.95, rate(ledger_posting_duration_seconds_bucket[2m]))

# P99 posting latency
histogram_quantile(0.99, rate(ledger_posting_duration_seconds_bucket[2m]))

# GC pause max
jvm_gc_pause_seconds_max
```

## 5. Issues

| ID | Severity | Description | Logs / Trace |
|---|---|---|---|

## 6. Conclusion

- [ ] PASSED — all gates green
- [ ] FAILED — see issues above

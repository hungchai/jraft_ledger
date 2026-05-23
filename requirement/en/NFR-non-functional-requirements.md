# NFR — Non-Functional Requirements Specification

**Document Version**: v0.4
**Function**: Non-Functional Requirements (NFR)
**System**: Next-Gen Internal Ledger Platform
**Status**: Draft for Review

> **v0.4 Change Summary**: NFR-9 added Prometheus/Grafana implementation details (endpoints, services, dashboard configuration).
> **v0.3 Change Summary**: Added NFR-16 (Raft cluster size and fault tolerance) and NFR-17 (Node sync monitoring).
> **v0.2 Change Summary**: Added NFR-13 (JVM & GC), NFR-14 (Account Queue), NFR-15 (accountSeq Overflow Policy); NFR-9 Observability supplemented with GC pause alert and accountSeq gap alert.

---

## 1. Performance

| Metric | Target | Test Condition |
|---|---|---|
| Posting P95 latency | ≤ 3ms | 1000 concurrent, including hotspot account (COMPANY_ACC) |
| Posting P99 latency | ≤ 10ms | Same as above |
| Balance Query P95 (Active account) | ≤ 2ms | Read in-memory State Machine |
| Balance Query P95 (Inactive account) | ≤ 5ms | Read RocksDB warm-up |
| Journal point query P95 | ≤ 10ms | MySQL View Layer, index hit |
| Account transaction query P95 (50 rows) | ≤ 30ms | MySQL View Layer |
| Manual Adjustment Approve P95 | ≤ 10ms | Through Raft |
| Reversal P95 | ≤ 5ms | Through Raft |
| Learner sync latency (normal load) | ≤ 1s | Raft Leader → MySQL View Layer |
| EOD full process (including L1/L2 reconciliation) | ≤ 30 minutes | 1 million Journals per day |

---

## 2. Throughput

| Metric | Target |
|---|---|
| Posting TPS (peak) | ≥ 10,000 TPS |
| Balance Query QPS | ≥ 50,000 QPS |
| Journal Query QPS | ≥ 5,000 QPS |

---

## 3. Availability

| Metric | Target |
|---|---|
| System availability (annual) | ≥ 99.99% (annual downtime ≤ 52 minutes) |
| Raft Leader failure recovery time (RTO) | ≤ 30 seconds (including election + State Machine recovery) |
| Planned maintenance downtime window | ≤ 30 minutes per month (non-EOD period) |
| Multi-AZ deployment | 3 nodes across AZs, allows 1 AZ failure without service impact |

---

## 4. Durability

| Metric | Target |
|---|---|
| RPO (Recovery Point Objective) | 0 (Raft Quorum commit is durable; no confirmed ledger data lost) |
| RTO (Recovery Time Objective) | ≤ 1 minute (Snapshot + Replay) |
| Journal retention period | ≥ 7 years (compliance requirement) |
| Audit Log retention period | ≥ 7 years |

---

## 5. Consistency

| Scenario | Consistency Level |
|---|---|
| Posting / Reversal / Adjustment write | **Strong consistency** (Raft Quorum commit) |
| Balance Query (real-time) | **Strong consistency** (read Leader in-memory State Machine) |
| Journal Query | **Eventual consistency** (Learner sync, delay ≤ 1s) |
| Reconciliation | **Eventual consistency** (completed within T+0 day) |

---

## 6. Immutability

- All posted JournalLines **prohibit UPDATE / DELETE**
- Only append is allowed (new Journal / Reversal / Adjustment)
- Both RocksDB and MySQL View Layer maintain JournalLine in append-only mode

---

## 7. Idempotency

- All write operations (Posting / Reversal / Adjustment Approve) support idempotency
- Idempotency Key: `requestId` (UUID v7), TTL ≥ 24 hours
- Retrying with the same `requestId` returns the original result without duplicate posting
- Dual guarantee: In-memory idempotency store + DB unique constraint

---

## 8. Security

| Requirement | Description |
|---|---|
| Authentication | All APIs require JWT / mTLS authentication |
| Authorization | RBAC, distinguishing read-only role (Viewer), operator role (Operator), approval role (Checker), admin role (Admin) |
| Maker-Checker | Manual Adjustment requires mandatory dual review, cannot be bypassed |
| Audit Log | All write operations record operator, time, IP, traceId, retained for 7 years |
| Sensitive Data | Account ID and amounts are desensitized in logs |

---

## 9. Observability [v0.3 Update]

| Requirement | Description |
|---|---|
| Distributed tracing | All requests carry traceId / spanId, integrated with Jaeger / Zipkin |
| Metrics | Prometheus exposes TPS, P50/P95/P99 latency, Queue backlog, Raft term, Learner lag, **GC pause time, Account Queue depth per account** |
| Prometheus endpoint | `/actuator/prometheus` — exposed by all ledger nodes (8081–8083) and projection (8089) |
| Prometheus Server | `http://localhost:9090` — scrape interval 15s, targets: ledger-node-1/2/3:8080, ledger-projection:8089 |
| Grafana | `http://localhost:3000` — default dashboard includes Posting P95, Balance Query latency, Raft leader status, Queue depth, GC pause |
| Alerts (original) | Posting P99 > 50ms, Queue backlog > 1000, Learner lag > 10s, L1 reconciliation failure → PagerDuty |
| Alerts (new) | **Any single GC pause > 5ms → PagerDuty**; **BalanceChangeEvent accountSeq gap detection failure → PagerDuty**; **Any accountSeq ≥ Long.MAX_VALUE × 80% → PagerDuty (theoretical warning, should never trigger)** |
| Logs | Structured JSON logs with journalId, requestId, accountId, traceId |
| Every ledger traceable | Any balance change can be traced to source event, operator, rule version, journal chain within 5 minutes |

---

## 10. Capacity Planning

| Metric | Assumed Value | Description |
|---|---|---|
| Total accounts | 1,000,000 | Active accounts approximately 100,000 resident in memory |
| Daily Journal count | 5,000,000 | 5 million ledger transactions per day |
| Average JournalLines per Journal | 4 | RFQ scenarios typically 4 lines |
| Daily JournalLine count | 20,000,000 | |
| RocksDB daily growth | ~10 GB | Estimated 500 bytes / JournalLine |
| MySQL View Layer daily growth | ~20 GB | Including indexes |
| State Machine memory (Active accounts) | ~2 GB | 100,000 accounts × 5 BalanceType × ~4KB |
| Raft Log Snapshot interval | 100,000 entries | Approximately one snapshot per minute (10,000 TPS peak) |

---

## 11. Disaster Recovery (DR)

| Requirement | Description |
|---|---|
| Multi-AZ deployment | 3 Raft nodes distributed across 3 AZs |
| Cross-DC disaster recovery | Raft Learner can be deployed in remote DC as DR node |
| RocksDB backup | Daily full RocksDB checkpoint backup to object storage (S3 / OSS) |
| Recovery drill | Conduct one full DR drill per quarter, verifying RTO ≤ 1 minute |

---

## 12. Technical Constraints

| Constraint | Description |
|---|---|
| Language / Framework | Java 21 + Spring Boot 3, using Virtual Threads |
| Raft library | SOFAJRaft (evaluate Apache Ratis as alternative) |
| Local persistence | RocksDB (Java API) |
| View Layer DB | MySQL 8.0+ (MyBatis, ORM prohibited) |
| Message bus | Kafka (Learner sync outputs ledger events for downstream consumption) |
| Prohibited | Hibernate / JPA / Redis (write path) / direct MySQL writes bypassing Raft |

---

## 13. JVM & GC [v0.2 New]

The P95 ≤ 3ms target under 10,000 TPS makes GC pause the main uncontrollable latency source. The default G1GC pause target is 200ms, far exceeding the entire Posting P95 budget, so low-latency GC must be explicitly mandated.

### 13.1 GC Collector

| Requirement | Specification |
|---|---|
| **Mandatory** | ZGC (`-XX:+UseZGC`) or Shenandoah (`-XX:+UseShenandoahGC`), choose one, **G1GC / ParallelGC prohibited** |
| Recommended | **ZGC** (Java 21 Production-ready, concurrent, pause < 1ms) |
| Alternative | Shenandoah (similar pause characteristics, suitable for smaller heaps) |
| **Prohibited** | G1GC (default), ParallelGC — pause unpredictable, cannot guarantee P99 ≤ 10ms |

### 13.2 JVM Startup Parameters (State Machine / Raft Leader node)

```bash
# GC
-XX:+UseZGC
-XX:MaxGCPauseMillis=1          # ZGC concurrent, pause target < 1ms

# Heap: fixed size, avoid resize triggering Full GC
-Xms8g
-Xmx8g

# GC Logging (integrated with Prometheus GCEasy / JVM metrics exporter)
-Xlog:gc*:file=/var/log/ledger/gc.log:time,uptime:filecount=10,filesize=50m

# Virtual Thread (Java 21)
# No extra parameters needed, Spring Boot 3 + Virtual Threads enabled by default
```

### 13.3 GC Pause Budget

| Scenario | Max Allowed GC Pause | Description |
|---|---|---|
| Normal operation | ≤ 1ms (ZGC concurrent) | Does not affect Posting P95 |
| Worst case | ≤ 5ms | Exceeding this triggers PagerDuty alert |
| **Prohibited** | > 10ms (P99 budget) | Single GC pause exceeding P99 budget indicates configuration error |

### 13.4 Hot Path Object Allocation Principles

State Machine apply() executes 10,000 times per second at 10,000 TPS peak; hot path heap allocation directly affects GC frequency:

| Principle | Description |
|---|---|
| **BalanceEntry reuse** | Account Worker thread (Virtual Thread, per-account serial) can use ThreadLocal pool to reuse `BalanceEntry`, avoiding new object creation on each apply |
| **WriteBatch serialization buffer reuse** | RocksDB `WriteBatch` serialization uses ThreadLocal `ByteBuffer` (direct, off-heap), avoiding `byte[]` allocation on each apply |
| **Immutable record design** | `AccountBalanceKey` uses Java record, JVM can perform escape analysis optimization, reducing heap allocation |
| **Avoid boxing** | balanceStore / idempotencyStore values use primitive-friendly structures, avoiding `Long` / `Double` autoboxing |

### 13.5 GC Metrics (Prometheus)

```
# Required JVM GC metrics:
jvm_gc_pause_seconds{cause, gc}         # Each GC pause duration
jvm_gc_pause_seconds_max                # Recent max pause
jvm_memory_used_bytes{area="heap"}      # Heap usage
jvm_memory_max_bytes{area="heap"}       # Heap limit
jvm_gc_live_data_size_bytes             # Live data size (ZGC)

# Alert rules (Prometheus AlertManager):
ALERT GCPauseTooLong
  IF jvm_gc_pause_seconds_max > 0.005   # 5ms
  FOR 1m
  SEVERITY critical
  ANNOTATIONS { summary = "GC pause exceeded 5ms, P99 at risk" }
```

---

## 14. Account Queue Design Constraints [v0.2 New]

Account Queue is the core queuing mechanism of the Ledger write path; each account has an independent queue, guaranteeing per-account serialization.

### 14.1 Current Implementation

```
Queue type: java.util.concurrent.LinkedBlockingQueue
Worker:     Java 21 Virtual Thread (one Virtual Thread worker per Account Queue)
Deployment: per-account, dynamically created, inactive account queues automatically reclaimed when no requests
```

### 14.2 Queue Capacity Design

| Parameter | Value | Description |
|---|---|---|
| Single-account Queue capacity limit | 1,000 requests | Exceeding triggers backpressure (HTTP 429 / gRPC RESOURCE_EXHAUSTED) |
| Global Request Queue capacity | 50,000 requests | All accounts' entry queue buffer, waiting before routing by accountId |
| Queue backlog alert threshold | Any account queue depth > 500 for 30s | Indicates the account's request rate exceeds State Machine processing capacity |
| Queue full behavior | **Fast-fail**, immediately return HTTP 429, no caller blocking | Prevents caller-side timeout accumulation |

### 14.3 Upgrade Path (if GC pressure is too high)

`LinkedBlockingQueue` allocates a `Node<E>` object on each `offer()`, creating a large number of short-lived objects per second at 10,000 TPS peak. If GC tuning still cannot meet P99, upgrade via the following path, **without modifying Raft or State Machine architecture**:

```
Phase 1 (default): LinkedBlockingQueue
  → Simple, sufficient, Java standard library

Phase 2 (if GC pressure is visible): JCTools MpscArrayQueue
  → Lock-free MPSC (Multi-Producer Single-Consumer)
  → No Node objects, reduce GC allocation ~60%
  → Pre-allocated fixed-size array, avoid dynamic expansion
  → Similar API, minimal changes

Phase 3 (if Phase 2 still insufficient): Agrona ManyToOneConcurrentArrayQueue
  → Off-heap, completely zero allocation
  → Requires Agrona dependency, complexity increases
```

> **Current choice is Phase 1**; Phase 2 / 3 only activated if performance tests (TC-NFR-01 / TC-NFR-02) fail to meet targets.

### 14.4 Backpressure Mechanism

```
Client → HTTP/gRPC → Global Request Queue
                             │
                    Queue full (>50,000)?
                             │ YES
                             ▼
                    HTTP 429 / RESOURCE_EXHAUSTED (immediate return)

                             │ NO
                             ▼
                    Account Queue routing (by accountId)
                             │
                    Account Queue full (>1,000)?
                             │ YES
                             ▼
                    HTTP 429 (single-account backpressure)

                             │ NO
                             ▼
                    Account Worker → Raft → State Machine
```

---

## 15. accountSeq Overflow Policy [v0.2 New]

`accountSeq` is a monotonically increasing sequence number per-account per-balanceType per-currency, used for downstream gap detection of BalanceChangeEvent.

### 15.1 Overflow Analysis

```
Type: long (64-bit signed, max = 9,223,372,036,854,775,807, approx 9.2 × 10¹⁸)

Worst-case estimate (hotspot account COMPANY_FX_ACC):
  10,000 TPS × 1 JournalLine / posting = 10,000 seq increments / second
  Overflow time = 9.2 × 10¹⁸ ÷ 10,000 ÷ 86,400 ÷ 365 ≈ 29,247,120 years

Conclusion: long will not overflow in any foreseeable business scenario.
```

### 15.2 Design Decision

| Decision | Reason |
|---|---|
| **Use `long`, not `BigInteger`** | 29+ million year lifespan, no practical overflow risk; `BigInteger` introduces heap allocation and serialization complexity |
| **Prohibit wrap-around** | If `long` overflows and starts from negative, downstream consumers will misjudge as gap, triggering massive false alerts; unacceptable |
| **Do not use unsigned long** | Java does not natively support unsigned long; `Long.compareUnsigned()` is usable but increases code comprehension cost, insufficient benefit |

### 15.3 Early Warning Mechanism

Although overflow is impossible, an alert is still needed as a safety net:

```java
// In State Machine apply(), check once after accountSeq increment
private static final long OVERFLOW_WARN_THRESHOLD = Long.MAX_VALUE / 100 * 80;
// ≈ 7.37 × 10¹⁸, ~5.86 × 10¹⁸ (~18,636,500 years) until overflow

if (nextSeq >= OVERFLOW_WARN_THRESHOLD) {
    // This log should never appear under normal circumstances
    log.error("[SEQ_OVERFLOW_WARN] accountId={} balanceType={} currency={} seq={}",
        key.accountId(), key.balanceType(), key.currency(), nextSeq);
    // Also trigger PagerDuty (see NFR-9 alert rules)
}
```

```
# Prometheus alert rule
ALERT AccountSeqOverflowRisk
  IF ledger_account_seq_max > 7370000000000000000   # 80% of Long.MAX_VALUE
  FOR 1m
  SEVERITY critical
  ANNOTATIONS { summary = "accountSeq approaching Long.MAX_VALUE — investigate immediately" }
```

---

## 16. Raft Cluster Size and Fault Tolerance [v0.3 New]

Raft protocol's data safety and consistency are based on the majority (Quorum) mechanism: any log commit must receive successful replication and confirmation from more than half of the nodes in the cluster. The total number of nodes follows the **N = 2F + 1** formula, where N is the total number of nodes and F is the number of tolerable failed nodes.

### 16.1 Minimum Configuration

| Total Voting Nodes | Follower Count | Quorum | Tolerable Failures | Suitable Scenario |
|-------------------|---------------|--------|-------------------|-------------------|
| 3 | 2 | 2 | 1 | Development / Testing / Low-risk environments |
| 5 | 4 | 3 | 2 | **Financial-grade production** |

> **2 nodes cannot operate.** 2-node Quorum = 2; any 1 node failure loses majority, Leader cannot commit any new logs, system unavailable. Raft's minimum practical configuration is 3 Voting Nodes.

### 16.2 Production Recommended Configuration: 5 Nodes

```
3 Voting Nodes (1 Leader + 2 Follower)
  ├─ Participate in Raft voting and log replication
  ├─ Deployed across 3 AZs, one voting node per AZ
  └─ Quorum = 3, allows 2 voting node failures simultaneously

2 Learner Nodes (non-voting)
  ├─ Do not participate in voting, do not affect Quorum calculation
  ├─ Asynchronously sync Raft Log → MySQL View Layer
  └─ Can be deployed in remote DC as DR node (optional)
```

| Comparison Dimension | 3 Voting Nodes | 5 Nodes (3 Voting + 2 Learner) |
|---------|---------------|-------------------------------|
| Quorum | 2 | 3 |
| Tolerate voting node failure | 1 | 2 |
| Rolling upgrade | Higher risk (only 1 node redundancy) | Can restart one by one without affecting Quorum |
| AZ-level failure | Allows 1 AZ failure | Allows 2 AZ failures (including voting) |
| Learner horizontal scaling | Requires additional deployment | Built-in 2 Learners, can scale on demand |
| Suitable scenario | Development / Testing | **Financial-grade production** |

### 16.3 Diminishing Marginal Returns

Beyond 5 Voting Nodes, every additional 2 nodes only tolerates 1 more failure, but cost grows non-linearly:

| Node Increase | Additional Fault Tolerance Benefit | Cost |
|-----------|------------|------|
| 3 → 5 | +1 fault tolerance (1 → 2) | Network overhead +67%, acceptable |
| 5 → 7 | +1 fault tolerance (2 → 3) | Network overhead +40%, larger Quorum, higher election contention probability |
| 7 → 9 | +1 fault tolerance (3 → 4) | Replication delay noticeably increases, operational complexity significant |

> **Conclusion: 5 Voting Nodes (with optional Learners) is the optimal balance for financial scenarios.** 7+ Voting Nodes only considered for extreme availability requirements (≥ 99.999%), and recommended to be combined with Multi-Raft-Group sharding to control single cluster size.

### 16.4 Relationship with Other NFRs

| Related Section | Relationship |
|---------|------|
| NFR-3 Availability | Cluster size directly determines whether availability targets are achievable (≥ 99.99% requires ≥ 3 Voting Nodes across AZs) |
| NFR-4 Durability | Quorum commit guarantees RPO = 0; more nodes means more replicas |
| NFR-5 Consistency | Strong consistency relies on Quorum mechanism; voting node count determines consistency safety boundary |
| NFR-11 Disaster Recovery | Learner can be deployed as remote DR node without affecting online Quorum |
| ADR-001 §5.1 | Architecture decision background for cluster configuration and Raft library selection |

---

## 17. Node Sync Monitoring [v0.3 New]

Raft quorum guarantees committed data durability, but it does not automatically expose per-node replication lag or follower health in a format usable by operators. A dedicated monitoring endpoint and derived metrics are required to detect split-brain, network partitions, or slow followers before they impact availability.

### 17.1 Endpoint

```
GET /ledger/cluster/raft-status
```

**Response example (follower node):**

```json
{
  "nodeId": "node2",
  "isLeader": false,
  "term": 5,
  "lastAppliedIndex": 1247,
  "peers": ["node1:28080", "node2:28080", "node3:28080"],
  "alivePeers": []
}
```

**Response example (leader node):**

```json
{
  "nodeId": "node1",
  "isLeader": true,
  "term": 5,
  "lastAppliedIndex": 1248,
  "peers": ["node1:28080", "node2:28080", "node3:28080"],
  "alivePeers": ["node2:28080", "node3:28080"]
}
```

### 17.2 Metric Semantics

| Field | Type | Description |
|---|---|---|
| `nodeId` | string | Unique node identifier (matches `ledger.group-id` in config) |
| `isLeader` | boolean | Whether this node is the current Raft Leader |
| `term` | long | Current Raft term; diverging terms across nodes indicate election activity or split-brain |
| `lastAppliedIndex` | long | Index of the last log entry applied to this node's State Machine |
| `peers` | string[] | Configured peer list (from `PEER_NODES` env var or fallback to self) |
| `alivePeers` | string[] | Peers the Leader considers alive (empty on followers because SOFAJRaft only exposes this to Leader) |

### 17.3 Replication Lag Interpretation

Replication lag is derived by comparing `lastAppliedIndex` across nodes polled via the same endpoint:

```
lag(node) = leader.lastAppliedIndex - node.lastAppliedIndex
```

| Lag Condition | Meaning | Operator Action |
|---|---|---|
| `lag == 0` on all nodes | Fully synced cluster | None |
| `0 < lag ≤ 10` for ≤ 5s | Normal transient lag | Monitor |
| `lag > 100` for > 10s | Slow follower or network partition | Investigate follower GC / network; consider restarting follower |
| `lag increases monotonically` | Follower is stalled or has crashed | Restart follower node; if persists, replace node and trigger snapshot restore |
| `term differs across nodes` | Split-brain or ongoing election | Check quorum; ensure majority nodes can reach each other; do not force-promote a follower manually |

### 17.4 Alert Thresholds

| Alert Name | Condition | Severity | Response |
|---|---|---|---|
| `RaftFollowerLagHigh` | Any follower's `lastAppliedIndex` lags leader by > 100 entries for > 30s | WARNING | Page on-call; investigate follower performance |
| `RaftFollowerLagCritical` | Any follower's `lastAppliedIndex` lags leader by > 1000 entries for > 60s | CRITICAL | Page on-call; prepare follower restart or replacement |
| `RaftTermDivergence` | Any two nodes report different `term` for > 10s | CRITICAL | Page on-call; possible split-brain — verify quorum before taking action |
| `RaftLeaderMissing` | No node reports `isLeader == true` for > 30s | CRITICAL | Page on-call; cluster has lost leadership — check network partition, restart nodes if necessary |
| `RaftAlivePeersLow` | Leader's `alivePeers` count < (`peers` count / 2) for > 10s | WARNING | Page on-call; minority cluster, at risk of losing quorum |

### 17.5 Integration with Observability Stack

- **Prometheus**: A sidecar or the application itself should expose `ledger_raft_last_applied_index{node_id}` as a Gauge. Lag can be computed in PromQL:
  ```promql
  max(ledger_raft_last_applied_index) - ledger_raft_last_applied_index
  ```
- **Health Checks**: The `/actuator/health` endpoint (or equivalent) should include a Raft indicator that returns `DOWN` when `lastAppliedIndex` is 0 for > 60s after startup (indicating the node has not joined the cluster).
- **Dashboards**: Grafana panel showing `lastAppliedIndex` per node, leader term, and alive peer count.

### 17.6 Standalone Mode

When Raft is disabled (single-node dev / test), the endpoint returns:

```json
{
  "mode": "standalone"
}
```

No replication lag alerts should fire in this mode.

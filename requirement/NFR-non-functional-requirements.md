# NFR — Non-Functional Requirements Specification

**Document Version**: v0.3
**System**: Next-Gen Internal Ledger Platform
**Status**: Draft for Review

---

| | | | Metric | Target | Test Condition |
|---|---|---|---|
| | ≤ 3ms | | Posting P95 Latency | ≤ 3ms | 1000 concurrency, with hotspot account (COMPANY_ACC) |
| | ≤ 10ms | | Posting P99 Latency | ≤ 10ms | Same as above |
| | ≤ 2ms | | Balance Query P95 (Active Account) | ≤ 2ms | Read in-memory State Machine |
| | ≤ 5ms | | Balance Query P95 (Inactive Account) | ≤ 5ms | Read RocksDB warm-up |
| | ≤ 10ms | | Journal Point Query P95 | ≤ 10ms | MySQL View Layer, index hit |
| | ≤ 30ms | MySQL View Layer | Account Ledger Query P95 (50 records) | ≤ 30ms | MySQL View Layer |
| Manual Adjustment Approve P95 | ≤ 10ms | | Manual Adjustment Approve P95 | ≤ 10ms | Via Raft |
| Reversal P95 | ≤ 5ms | | Reversal P95 | ≤ 5ms | Via Raft |
| | ≤ 1s | Raft Leader → MySQL View Layer | Learner Sync Delay (normal load) | ≤ 1s | Raft Leader → MySQL View Layer |
| | | | EOD Full Flow (incl. L1/L2 reconciliation) | ≤ 30 minutes | 1 million Journals per day |

---

| | | Metric | Target |
|---|---|---|
| | ≥ 10,000 TPS | Posting TPS (Peak) | ≥ 10,000 TPS |
| Balance Query QPS | ≥ 50,000 QPS | Balance Query QPS | ≥ 50,000 QPS |
| Journal Query QPS | ≥ 5,000 QPS | Journal Query QPS | ≥ 5,000 QPS |

---

| | | Metric | Target |
|---|---|---|
| | | System Availability (annual) | ≥ 99.99% (annual downtime ≤ 52 minutes) |
| | | Raft Leader Failure Recovery Time (RTO) | ≤ 30 seconds (incl. election + State Machine recovery) |
| | | Planned Maintenance Downtime Window | ≤ 30 minutes per month (non-EOD hours) |
| | | Multi-AZ Deployment | 3 nodes across AZs, allows 1 AZ failure without service impact |

---

| | | Metric | Target |
|---|---|---|
| | | RPO (Recovery Point Objective) | 0 (Raft Quorum commit is durable, no confirmed accounting lost) |
| | | RTO (Recovery Time Objective) | ≤ 1 minute (Snapshot + Replay) |
| | | Journal Retention Period | ≥ 7 years (compliance requirement) |
| | | Audit Log Retention Period | ≥ 7 years |

---

| | | Scenario | Consistency Level |
|---|---|---|
| Posting / Reversal | | Posting / Reversal / Adjustment Write | **Strong consistency** (Raft Quorum commit) |
| | | Balance Query (real-time) | **Strong consistency** (read Leader in-memory State Machine) |
| Journal Query | | Journal Query | **Eventual consistency** (Learner sync, delay ≤ 1s) |
| | | Reconciliation | **Eventual consistency** (T+0 intraday completion) |

---

- All posted JournalLines **prohibit UPDATE / DELETE**
- Reversal / Reversal / Adjustment)
- Both RocksDB and MySQL View Layer maintain JournalLine in append-only mode

---

- All write operations (Posting / Reversal / Adjustment Approve) support idempotency
- Idempotency Key: `requestId` (UUID v7), TTL ≥ 24 hours
- Retrying with same `requestId` returns original result, no duplicate posting
- In-memory idempotency store + DB unique constraint dual protection

---

| | | Requirement | Description |
|---|---|---|
| | | Authentication | All APIs require JWT / mTLS authentication |
| | | Authorization | RBAC, distinguishing read-only role (Viewer), operator role (Operator), approval role (Checker), admin role (Admin) |
| Maker-Checker | | Maker-Checker | Manual Adjustment requires mandatory dual review, cannot be bypassed |
| | | Audit Log | All write operations record operator, time, IP, traceId, retained for 7 years |
| | | Sensitive Data | Account ID and amount are masked in logs |

---

| | | Requirement | Description |
|---|---|---|
| | Zipkin | Distributed Tracing | All requests carry traceId / spanId, integrated with Jaeger / Zipkin |
| Metrics | | Metrics | Prometheus exposes TPS, P50/P95/P99 latency, Queue backlog, Raft term, Learner lag, **GC pause time, Account Queue depth per account** |
| | | Alerts (existing) | Posting P99 > 50ms, Queue backlog > 1000, Learner lag > 10s, L1 reconciliation failure → PagerDuty |
| | | Alerts (new) | **Any single GC pause > 5ms → PagerDuty**; **BalanceChangeEvent accountSeq gap detection failure → PagerDuty**; **Any accountSeq ≥ Long.MAX_VALUE × 80% → PagerDuty (theoretical early warning, should never trigger)** |
| | | Logging | Structured JSON logs, with journalId, requestId, accountId, traceId |
| | | Each Accounting Traceable | Any balance change can be traced to source event, operator, rule version, journal chain within 5 minutes |

---

| | | | Metric | Assumption | Description |
|---|---|---|---|
| | 1,000,000 | | Total Accounts | 1,000,000 | ~100,000 active accounts resident in memory |
| | 5,000,000 | | Daily Journal Count | 5,000,000 | 5 million accounting entries per day |
| | 4 | | Avg JournalLines per Journal | 4 | RFQ scenario typically has 4 lines |
| | 20,000,000 | | Daily JournalLine Count | 20,000,000 | |
| | ~10 GB | JournalLine | RocksDB Daily Increment | ~10 GB | Estimated 500 bytes / JournalLine |
| | ~20 GB | | MySQL View Layer Daily Increment | ~20 GB | Including indexes |
| | ~2 GB | | State Machine Memory (Active Accounts) | ~2 GB | 100,000 accounts × 5 BalanceType × ~4KB |
| | | | Raft Log Snapshot Interval | 100,000 entries | ~Once per minute snapshot (10,000 TPS peak) |

---

| | | Requirement | Description |
|---|---|---|
| | | Multi-AZ Deployment | 3 Raft nodes distributed across 3 AZs |
| | | Cross-DC Disaster Recovery | Raft Learner can be deployed in remote DC as DR node |
| | | RocksDB Backup | Daily full RocksDB checkpoint backup to object storage (S3 / OSS) |
| | | Recovery Drill | Conduct full DR drill quarterly, verify RTO ≤ 1 minute |

---

## 12. Technical Constraints

| | | Constraint | Description |
|---|---|---|
| | | Language / Framework | Java 21 + Spring Boot 3, using Virtual Threads |
| | | Raft Library | SOFAJRaft (evaluate Apache Ratis as backup) |
| | | Local Persistence | RocksDB (Java API) |
| View Layer DB | | View Layer DB | MySQL 8.0+ (MyBatis, ORM prohibited) |
| | | Message Bus | Kafka (Learner synchronously outputs accounting events for downstream systems) |
| | Hibernate / JPA | Prohibited | Hibernate / JPA / Redis (write path) / direct MySQL write bypassing Raft |

---

With the P95 ≤ 3ms target at 10,000 TPS, GC pause is the main uncontrollable latency source. Default G1GC pause target is 200ms, far exceeding the entire Posting P95 budget — low-latency GC must be forcibly specified.

### 13.1 GC Collector

| | | Requirement | Specification |
|---|---|---|
| | ParallelGC** | **Mandatory Use** | ZGC (`-XX:+UseZGC`) or Shenandoah (`-XX:+UseShenandoahGC`), choose one, **G1GC / ParallelGC prohibited** |
| | | Recommended Choice | **ZGC** (Java 21 Production-ready, concurrent, pause < 1ms) |
| | | Alternative | Shenandoah (similar pause characteristics, suitable for smaller heap) |
| | | **Prohibited** | G1GC (default), ParallelGC — pause unpredictable, cannot guarantee P99 ≤ 10ms |

### Raft Leader Node)

```bash
# GC
-XX:+UseZGC
-XX:MaxGCPauseMillis=1 # ZGC concurrent, pause target < 1ms

# Heap
# Heap: Fixed size, avoid resize triggering Full GC
-Xms8g
-Xmx8g

# GC Logging (integrated with Prometheus GCEasy / JVM metrics exporter)
-Xlog:gc*:file=/var/log/ledger/gc.log:time,uptime:filecount=10,filesize=50m

# Virtual Thread (Java 21)
# No additional parameters needed, Spring Boot 3 + Virtual Threads enabled by default
```

### 13.3 GC Pause Budget

| | | | Scenario | Max Allowed GC Pause | Description |
|---|---|---|
| | | | Normal Operation | ≤ 1ms (ZGC concurrent) | Does not affect Posting P95 |
| | ≤ 5ms | | Worst Case | ≤ 5ms | Exceeding this value triggers PagerDuty alert |
| | | | **Prohibited** | > 10ms (P99 budget) | GC pause exceeding P99 budget once is a configuration error |

### 13.4 Hot Path Object Allocation Principles

State Machine apply() executes 10,000 times per second at 10,000 TPS peak — hot path heap allocation directly affects GC frequency:

| | | Principle | Description |
|---|---|---|
| | | **BalanceEntry Reuse** | Account Worker thread (Virtual Thread, per-account serial) can use ThreadLocal pool to reuse `BalanceEntry`, avoiding new object creation per apply |
| | | **WriteBatch Serialization Buffer Reuse** | RocksDB `WriteBatch` serialization uses ThreadLocal `ByteBuffer` (direct, off-heap), avoiding `byte[]` allocation per apply |
| | | **Immutable Record Design** | `AccountBalanceKey` uses Java record, JVM can do escape analysis optimization, reducing heap allocation |
| | balanceStore / `Double` autoboxing | **Avoid Boxing** | value of balanceStore / idempotencyStore uses primitive-friendly structure, avoids `Long` / `Double` autoboxing |

```
# 
# Required JVM GC metrics to expose:
jvm_gc_pause_seconds{cause, gc} # Each GC pause duration
jvm_gc_pause_seconds_max # Recent max pause
jvm_memory_used_bytes{area="heap"} # Heap usage
jvm_memory_max_bytes{area="heap"} # Heap limit
jvm_gc_live_data_size_bytes # Live data size (ZGC)

# 
# Alert rules (Prometheus AlertManager):
ALERT GCPauseTooLong
  IF jvm_gc_pause_seconds_max > 0.005 # 5ms
  FOR 1m
  SEVERITY critical
  ANNOTATIONS { summary = "GC pause exceeded 5ms, P99 at risk" }
```

---

Account Queue is the core queuing mechanism for the Ledger write path — one independent queue per account, ensuring per-account serialization.

### 14.1 Current Implementation

```
java.util.concurrent.LinkedBlockingQueue
Worker
Queue Type: java.util.concurrent.LinkedBlockingQueue
Worker: Java 21 Virtual Thread (one Virtual Thread worker per Account Queue)
Deployment: per-account, dynamically created, inactive account queues are automatically recycled when no requests
```

### 14.2 Queue Capacity Design

| | | | Parameter | Value | Description |
|---|---|---|
| | | | Per-account Queue capacity limit | 1,000 requests | Exceeding triggers backpressure (HTTP 429 / gRPC RESOURCE_EXHAUSTED) |
| | | | Global Request Queue capacity | 50,000 requests | All accounts' entry queue buffer, wait before routing by accountId |
| | | | Queue backlog alert threshold | Any account queue depth > 500 for 30s | Indicates that account's request rate exceeds State Machine processing capacity |
| | | | Queue full behavior | **Fast fail**, immediately return HTTP 429, do not block caller | Avoid caller-side timeout accumulation |

`LinkedBlockingQueue` allocates one `Node<E>` object per `offer()`, generating massive short-lived objects at 10,000 TPS peak. If GC tuning still cannot meet P99, upgrade via the following path, **without modifying Raft or State Machine architecture**:

```
Phase 1: LinkedBlockingQueue
Phase 1 (default): LinkedBlockingQueue
  → Simple, sufficient, Java standard library

Phase 2: JCTools MpscArrayQueue
  → Lock-free MPSC
Phase 2 (if GC pressure visible): JCTools MpscArrayQueue
  → Lock-free MPSC (Multi-Producer Single-Consumer)
  → No Node object, reduces GC allocation ~60%
  → Pre-allocated fixed-size array, avoids dynamic expansion
  → Similar API, minimal change

Phase 3: Agrona ManyToOneConcurrentArrayQueue
Phase 3 (if Phase 2 still insufficient): Agrona ManyToOneConcurrentArrayQueue
  → Off-heap, completely zero allocation
  → Requires Agrona dependency, complexity increases
```

> 3 are only activated when performance tests (TC-NFR-01 / TC-NFR-02) fail to meet targets.

### 14.4 Backpressure Mechanism

```
Client → HTTP/gRPC → Global Request Queue
                              │
                     Queue full (>50,000)?
                              │ YES
                              ▼
                     HTTP 429

                              │ NO
                              ▼
                     Account Queue routing
                              │
                     Account Queue full (>1,000)?
                              │ YES
                              ▼
                     HTTP 429

                              │ NO
                              ▼
                     Account Worker → Raft → State Machine
```

---

`accountSeq` is a monotonically increasing sequence number per account per balanceType per currency, used for downstream gap detection of BalanceChangeEvent.

### 15.1 Overflow Analysis

```
Type: long (64-bit signed, max = 9,223,372,036,854,775,807, approx. 9.2 × 10¹⁸)

Worst-case estimation (hotspot account COMPANY_FX_ACC):
  10,000 TPS × 1 JournalLine
  Overflow time = 9.2 × 10¹⁸ ÷ 10,000 ÷ 86,400 ÷ 365 ≈ 29,247,120 years

Conclusion: long will not overflow in any foreseeable business scenario.
```

### 15.2 Design Decision

| | | Decision | Reason |
|---|---|---|
| | | **Use `long`, not `BigInteger`** | 29+ million years lifespan, no actual overflow risk; `BigInteger` introduces heap allocation and serialization complexity |
| | | **Prohibit wrap-around** | If `long` overflows and starts from negative numbers, downstream consumer will mistakenly judge as gap and trigger massive false alarm, unacceptable |
| | | **Do not use unsigned long** | Java does not natively support unsigned long; while `Long.compareUnsigned()` is usable, it increases code comprehension cost with insufficient benefit |

### 15.3 Early Warning Mechanism

Although overflow is impossible, an alert is still needed as a safety net:

```java
// In State Machine apply(), perform a check after accountSeq increment
private static final long OVERFLOW_WARN_THRESHOLD = Long.MAX_VALUE / 100 * 80;
// ≈ 7.37 × 10¹⁸, approx. 5.86 × 10¹⁸ from overflow (~18,636,500 years)

if (nextSeq >= OVERFLOW_WARN_THRESHOLD) {
    // This log will never appear under normal circumstances
    log.error("[SEQ_OVERFLOW_WARN] accountId={} balanceType={} currency={} seq={}",
        key.accountId(), key.balanceType(), key.currency(), nextSeq);
    // Also trigger PagerDuty (see NFR-9 alert rules)
}
```

```
# Prometheus alert rules
ALERT AccountSeqOverflowRisk
  IF ledger_account_seq_max > 7370000000000000000 # 80% of Long.MAX_VALUE
  FOR 1m
  SEVERITY critical
  ANNOTATIONS { summary = "accountSeq approaching Long.MAX_VALUE — investigate immediately" }
```

---

Raft protocol's data safety and consistency are built on the majority decision (Quorum) mechanism: any log submission must receive successful replication and confirmation from more than half the nodes in the cluster. The total number of system nodes follows the **N = 2F + 1** formula, where N is the total node count and F is the number of tolerable faulty nodes.

### 16.1 Minimum Configuration

| | | Quorum | | |
| Total Voting Nodes | Follower Count | Quorum | Faults Tolerated | Applicable Scenario |
|----------------|------------|--------|-----------|---------|
| 3 | 2 | 2 | | |
| 3 | 2 | 2 | 1 node | Development / Testing / Low-risk environments |
| 5 | 4 | 3 | | |
| 5 | 4 | 3 | 2 nodes | **Financial-grade production environment** |

### 16.2 Production Recommended Configuration: 5 Nodes

```
3 Voting Nodes
  ├─ Participate in Raft voting and log replication
  ├─ Deployed across 3 AZs, one voting node per AZ
  └─ Quorum = 3, allows 2 voting nodes to fail simultaneously

2 Learner Nodes
  ├─ Do not participate in voting, do not affect Quorum calculation
  ├─ Asynchronously sync Raft Log → MySQL View Layer
  └─ Can be deployed in remote DC as DR node (optional)
```

| | 3 Voting Nodes | | Comparison Dimension | 3 Voting Nodes | 5 Nodes (3 Voting + 2 Learner) |
|---------|---------------|-------------------------------|---------------------|----------------|-------------------------------|
| Quorum | 2 | 3 | Quorum | 2 | 3 |
| | | | Tolerate voting node failure | 1 node | 2 nodes |
| | | | Rolling Upgrade | Higher risk (only 1 redundant) | Can restart one by one, no impact on Quorum |
| | | | AZ-level Failure | Allows 1 AZ failure | Allows 2 AZ failures (including voting) |
| | | | Learner Horizontal Scaling | Need additional deployment | Built-in 2 Learners, can scale on demand |
| | | | Applicable Scenario | Development / Testing | **Financial-grade production** |

### 16.3 Diminishing Marginal Returns

Beyond 5 Voting Nodes, adding 2 nodes only provides 1 more fault tolerance, but the cost grows non-linearly:

| | | | Node Increase | Additional Fault Tolerance Benefit | Cost |
|-----------|------------|------|---------------|----------------------------------|------|
| 3 → 5 | | | 3 → 5 | +1 fault tolerance (1 → 2) | Network overhead +67%, acceptable |
| 5 → 7 | | | 5 → 7 | +1 fault tolerance (2 → 3) | Network overhead +40%, larger Quorum, increased election competition probability |
| 7 → 9 | | | 7 → 9 | +1 fault tolerance (3 → 4) | Replication delay significantly increases, operational complexity significant |

### 16.4 Relationship with Other NFRs

| | | Related Chapter | Relationship |
|---------|------|-----------------|--------------|
| | | NFR-3 Availability | Cluster scale directly determines whether availability target can be achieved (≥ 99.99% requires ≥ 3 Voting Nodes across AZ) |
| | | NFR-4 Data Durability | Quorum commit guarantees RPO = 0; more nodes means more replicas |
| | | NFR-5 Consistency | Strong consistency depends on Quorum mechanism; voting node count determines consistency safety margin |
| | | NFR-11 Disaster Recovery | Learner can be deployed as remote DR node without affecting online Quorum |
| ADR-001 §5.1 | | ADR-001 §5.1 | Architecture decision background for cluster configuration and Raft library selection reasons |

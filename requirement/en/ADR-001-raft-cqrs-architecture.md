# ADR-001 Ledger Core Architecture Decision: Raft + CQRS + Account-Level Queue

**Decision Status**: Accepted
**Decision Date**: 2026-05-16
**Decision Maker**: Ledger Platform Team
**Scope**: F-002 Posting, F-003 Manual Adjustment, F-004 Reversal, F-005 Balance Query, F-006 Journal Query, F-007 Reconciliation, F-008 State Machine

> **v0.3 Change Summary**: Added Section 6.3 MySQL View Layer Sharding (ShardingSphere-JDBC configuration for journal_line).
> **v0.2 Change Summary**: Section 3.2 supplemented with full Multi-Account Coordinator implementation specification (Leader election mechanism, Timeout handling, N-account generic design); added Section 3.3 Multi-Account Task data structure; added risk items in Section 8.

---

## 1. Background and Problem

The Internal Ledger Platform needs to simultaneously satisfy three conflicting requirements:

1. **Synchronous atomic posting**: Every Posting / Reversal / Adjustment must be fully atomic; asynchronous posting is not allowed
2. **RFQ company account hotspot**: All client RFQ trades have the same company account as counterparty; traditional DB row locks cause severe lock contention under high concurrency
3. **Extremely low latency**: Posting P95 ≤ 3ms, Balance Query P95 ≤ 2ms

Traditional Spring Boot multi-node + PostgreSQL solutions cannot satisfy both atomicity and low latency under hotspot accounts; Shard / Rebalancing solutions introduce fund management complexity.

---

## 2. Decision

Adopt the **Raft + CQRS + Account-Level In-Memory Queue** architecture, referencing the Binance Ledger production solution.

### 2.1 Core Principles

- **Write path**: All ledger write operations (Posting / Reversal / Adjustment) go through the Raft consensus protocol, processed serially by the Leader node's in-memory State Machine, persisted to RocksDB, without direct writes to MySQL
- **Read path**: Balance queries read directly from the Leader in-memory State Machine; Journal / reconciliation queries read from the MySQL View Layer
- **Sync mechanism**: Raft Learner nodes listen to the Raft Log and asynchronously sync to MySQL for query and reconciliation use
- **Account serialization**: Each account maintains an in-memory queue, processed serially by a single virtual thread, eliminating account-level concurrency conflicts

### 2.2 Raft Library Selection

Adopt **SOFAJRaft** (Ant Group / Alibaba open source), reasons:
- Native Java, integrates well with Spring Boot
- Supports Multi-Raft-Group, enabling horizontal scaling by account grouping
- Production validated (same technology stack as Ant Financial)
- Supports Learner role, suitable for CQRS read-write separation

### 2.3 Overall Architecture

```
Client Request
      │
      ▼
┌─────────────────────────────────────────────────┐
│              Ledger Write Domain                 │
│                                                  │
│  ┌────────────┐   ┌──────────────────────────┐  │
│  │   Network  │   │       Raft Cluster        │  │
│  │   Layer    │   │  ┌────────┐  ┌─────────┐ │  │
│  │ (gRPC/HTTP)│   │  │ Leader │  │Follower │ │  │
│  └─────┬──────┘   │  │        │  │         │ │  │
│        │          │  │RocksDB │  │ RocksDB │ │  │
│  ┌─────▼──────┐   │  └────────┘  └─────────┘ │  │
│  │   Ledger   │◄──►       ↕ Raft Log          │  │
│  │   Layer    │   │  ┌─────────┐              │  │
│  │            │   │  │ Learner │ non-voting   │  │
│  │  Account   │   │  └────┬────┘              │  │
│  │  Queue     │   └───────│───────────────────┘  │
│  │  (per acct)│           │ async push            │
│  │            │           ▼                      │
│  │  State     │   ┌───────────────┐              │
│  │  Machine   │   │  View Layer   │              │
│  │ (in-memory │   │  (MySQL)      │              │
│  │  balance)  │   │  journal_line │              │
│  └────────────┘   │  account_bal  │              │
│                   │  snapshot     │              │
└───────────────────┴───────────────┴──────────────┘
         ↑ Write                  ↑ Read
    Posting / Rev / Adj      Journal / Recon / Report
```

---

## 3. Write Path Details

### 3.1 Request Pipeline

```
1. Network Layer     Receive HTTP/gRPC request, deserialize, place into request_queue
2. Ledger Layer      Take from request_queue, perform pre-validation (schema / auth)
                     Route by accountId to the corresponding Account Queue
3. Account Queue     One LinkedBlockingQueue per account
                     Each queue corresponds to one Java 21 Virtual Thread (worker)
4. Account Worker    Take task from queue, execute serially:
                     a. Idempotency check (in-memory idempotency map)
                     b. Balance validation (read in-memory State Machine)
                     c. Prepare Raft Command (serialize ledger instruction)
5. Raft Layer        Submit Command to Raft Group
                     Leader replicates to Follower, commits after reaching Quorum
6. State Machine     apply Raft log, execute ledger calculation:
                     a. Update in-memory balance
                     b. Generate journal_line record
                     c. Write RocksDB (persistence)
7. Response          Return result to client via response_queue
```

### 3.2 Multi-Account Journal Atomicity [v0.2 Detailed]

RFQ scenarios involve two (or more) accounts: CLIENT_ACC + COMPANY_ACC.

#### Core Design Principle: Queue as Lock

Each account has one `LinkedBlockingQueue`, consumed serially by a single Virtual Thread. **There is no explicit Lock / Mutex / synchronized**. The queue itself guarantees that only one transaction is executing for the same account at any given time; the lock is the queue position.

#### Deadlock Prevention: accountId Ascending Order

Multi-account scenarios must submit tasks in **lexicographically ascending** order by accountId, ensuring all requests acquire "Queue positions" in the same order, eliminating circular waits (Resource Ordering algorithm):

```
✅ Correct: All requests first take COMPANY_FX_ACC queue, then CLIENT_ACC_001 queue
❌ Incorrect: No ordering → possible mutual waiting → deadlock
```

#### Two-Phase Coordination Flow

```
Phase 1 – Placeholder (Coordinator responsibility)
  1. Extract all involved accountIds, sort in lexicographically ascending order
  2. Create MultiAccountTask (containing CountDownLatch + AtomicInteger + resultLatch)
  3. Sequentially push the same MultiAccountTask into each account's Account Queue
     → If Queue is full (> 1000), immediately return HTTP 429, no blocking

Phase 2 – Execution (Account Worker responsibility)
  Each Account Worker, after taking the MultiAccountTask:
  1. Call task.markReadyAndCheckLeader()
     → AtomicInteger increments, returns "whether this is the last ready"
  2. Call task.readyLatch.await(timeout=30ms)
     → Wait for all account Workers to be ready
     → Timeout → entire request TIMEOUT_WAITING_ACCOUNTS, see Timeout Handling
  3. If isLeader: submit RaftCommand, get result, task.setResult(), task.resultLatch.countDown()
     If not Leader: task.resultLatch.await(timeout=100ms), get task.getResult()
  4. Return result to caller response_queue
```

#### Leader Worker Election: Fixed as the Last Account's Worker After Sorting

```java
// Determined when Coordinator creates task; behavior is fully deterministic and easy to trace
class MultiAccountTask {
    private final String leaderAccountId;  // last element of sortedAccounts

    MultiAccountTask(List<String> sortedAccounts, RaftCommand command) {
        // Example: [COMPANY_FX_ACC, CLIENT_ACC_001] → leader = CLIENT_ACC_001
        this.leaderAccountId = sortedAccounts.get(sortedAccounts.size() - 1);
        this.totalAccounts   = sortedAccounts.size();
        this.readyLatch      = new CountDownLatch(totalAccounts);
        this.resultLatch     = new CountDownLatch(1);
        this.readyCount      = new AtomicInteger(0);
        this.command         = command;
    }

    // Each Worker calls once; returns true = I am Leader
    boolean markReadyAndCheckLeader(String myAccountId) {
        readyCount.incrementAndGet();
        readyLatch.countDown();
        return myAccountId.equals(leaderAccountId);
    }
}
```

#### Timeout Handling

```
readyLatch.await timeout (default 30ms):
  Cause: One account's Queue is severely backlogged, Worker cannot take task in time
  Handling:
    1. Workers that have already ready call task.cancel()
    2. All Workers receive cancelled signal, immediately return TIMEOUT_WAITING_ACCOUNTS
    3. Mark this task as void in each respective Queue (later Workers skip it after taking)
    4. Coordinator returns HTTP 503, caller can retry with requestId (idempotency guarantee)

resultLatch.await timeout (default 100ms):
  Cause: Raft commit timeout (Leader failure / network partition)
  Handling:
    1. Non-Leader Worker returns RAFT_TIMEOUT
    2. Coordinator returns HTTP 503, caller retries
    3. Idempotency mechanism guarantees retry safety (same requestId, no duplicate posting)
```

### 3.3 MultiAccountTask Data Structure [v0.2 New]

```java
class MultiAccountTask {
    // Input
    final String              requestId;
    final RaftCommand         command;
    final String              leaderAccountId;   // last accountId after sorting
    final int                 totalAccounts;

    // Coordination
    final CountDownLatch      readyLatch;        // wait for all Workers ready
    final CountDownLatch      resultLatch;       // wait for Leader to put result
    final AtomicInteger       readyCount;        // number of ready Workers
    volatile boolean          cancelled = false; // timeout cancellation flag

    // Result (Leader writes, other Workers read)
    volatile CommandResult    result;

    // Worker call: mark self ready, return whether is Leader
    boolean markReadyAndCheckLeader(String myAccountId) {
        readyCount.incrementAndGet();
        readyLatch.countDown();
        return myAccountId.equals(leaderAccountId);
    }

    void setResult(CommandResult r) {
        this.result = r;
        resultLatch.countDown();
    }

    CommandResult getResult() throws InterruptedException, TimeoutException {
        if (!resultLatch.await(100, MILLISECONDS)) {
            throw new TimeoutException("Raft result timeout");
        }
        return result;
    }

    void cancel() {
        this.cancelled = true;
        // Release all waiting awaits so Workers exit quickly
        while (readyLatch.getCount() > 0)  readyLatch.countDown();
        while (resultLatch.getCount() > 0) resultLatch.countDown();
    }
}
```

---

## 4. Read Path Details

### 4.1 Balance Query

- Read directly from Leader's in-memory State Machine
- No RocksDB, no MySQL
- P95 target: ≤ 2ms (pure in-memory read)

### 4.2 Journal / Audit / Reconciliation Query

- Read from MySQL View Layer (asynchronously synced by Learner)
- May have slight delay (usually < 1 second)
- P95 target: ≤ 100ms

### 4.3 Consistency Guarantees

- Balance Query is strongly consistent (read Leader in-memory, always latest)
- Journal Query is eventually consistent (Learner async sync, may have slight delay)
- Reconciliation scenarios allow eventual consistency (T+0 reconciliation allows minute-level delay)

---

## 5. High Availability Strategy

### 5.1 Raft Cluster Configuration

Raft's data safety is based on the majority (Quorum) mechanism: any log commit must receive confirmation from more than half of the nodes in the cluster. The total number of nodes follows the **N = 2F + 1** formula, where F is the number of tolerable failed nodes.

#### Minimum Configuration: 3 Voting Nodes

```
Minimum 3 Voting Nodes (1 Leader + 2 Follower)
  Reason: N = 2F + 1, if F ≥ 1, then N ≥ 3
  2 nodes cannot work: Quorum = ⌈N/2⌉ + 1, 2-node Quorum = 2, any 1 node failure loses majority, Leader cannot commit new logs
  Quorum = 2, allows 1 Voting Node failure
  Suitable for: development, testing, low-risk environments
```

#### Production Recommendation: 5 Nodes

```
3 Voting Nodes (1 Leader + 2 Follower) + 2 Learner Nodes (non-voting)

Voting Nodes:
  - Participate in Raft voting and log replication
  - Deployed across 3 AZs, one voting node per AZ
  - Quorum = 3, allows 2 voting node failures simultaneously
  - Supports rolling upgrades one by one (at least 2 voting nodes online)

Learner Nodes:
  - Do not participate in voting, do not affect Quorum calculation
  - Asynchronously sync Raft Log → MySQL View Layer
  - Can be deployed in remote DC as DR nodes
  - Can horizontally scale to improve Journal/Reconciliation query throughput
```

#### Node Scale and Fault Tolerance

| Total Voting Nodes | Quorum | Tolerable Failures | Learner (Recommended) | Suitable Scenario |
|-------------------|--------|-------------------|----------------------|-------------------|
| 3 | 2 | 1 | 0–2 | Development / Testing |
| 5 | 3 | 2 | 0–4 | **Financial-grade Production** |
| 7 | 4 | 3 | 0–6 | Extreme Availability (≥ 99.999%) |

> **5 nodes (3 Voting + 2 Learner) is the optimal balance for financial scenarios.** Beyond 5 Voting Nodes, every additional 2 nodes only tolerates 1 more failure, while replication delay, network overhead, and operational complexity all grow significantly. See [NFR §16](NFR-non-functional-requirements.md).

### 5.2 Leader Failure Recovery

```
Leader failure → Raft automatically elects new Leader
Election time: typically 150–300ms (SOFAJRaft default)
New Leader recovers State Machine from RocksDB + Raft Log replay
Recovery time: depends on log count since last snapshot
→ Need periodic State Machine Snapshot to control replay time
```

### 5.3 In-flight Request Handling

- During Leader switch, in-flight Raft Commands that have not reached Quorum will return errors to client
- Client retries with idempotencyKey, new Leader processes normally (idempotency guarantee)

---

## 6. Persistence Strategy

### 6.1 RocksDB (Write Domain)

- Stores Raft Log + State Machine Snapshot
- Each journal_line is written in WAL form, ensuring crash replay capability
- Periodic State Machine Snapshot to prevent unlimited Raft Log growth

### 6.2 MySQL (View Layer)

- Asynchronously written by Learner
- Stores complete journal, account_balance snapshot, reconciliation reports
- Read-only purpose, not on the write path

### 6.3 MySQL View Layer Sharding [v0.3 New]

The MySQL View Layer uses **ShardingSphere-JDBC** for horizontal sharding of the `journal_line` table. While the write path is fully handled by Raft + RocksDB, the View Layer must support high-volume journal queries and reconciliation without becoming a read bottleneck.

#### Why shard `journal_line` only

Per NFR-10 capacity planning, the system generates ~20 million `journal_line` rows per day. Query patterns are account-centric (e.g., "show all journals for account X"). Sharding by `account_id` ensures:

- **Single-account queries hit one shard**, keeping query latency bounded
- **Write distribution is uniform** (hash-based, not range-based), preventing hot shards
- **Other tables remain single-table** — `journal`, `account_balance`, `account`, and `balance_type_registry` are small enough or updated via `UPSERT` to not require sharding

#### Sharding topology

| Logical Table | Physical Tables | Sharding Column | Algorithm | Shard Count |
|---|---|---|---|---|
| `journal_line` | `journal_line_0` .. `journal_line_3` | `account_id` | `Math.abs(account_id.hashCode() % 4)` | 4 (configurable) |
| `journal` | `journal` (single) | — | — | 1 |
| `account_balance` | `account_balance` (single) | — | — | 1 |
| `account` | `account` (single) | — | — | 1 |

#### ShardingSphere-JDBC configuration (`sharding-config.yaml`)

```yaml
dataSources:
  ds_0:
    dataSourceClassName: com.zaxxer.hikari.HikariDataSource
    driverClassName: com.mysql.cj.jdbc.Driver
    jdbcUrl: jdbc:mysql://ledger-mysql:3306/ledger_view?allowPublicKeyRetrieval=true&useSSL=false
    username: ledger
    password: ledger123

rules:
- !SINGLE
  tables:
    - "*.*"
- !SHARDING
  tables:
    journal_line:
      actualDataNodes: ds_0.journal_line_${0..3}
      tableStrategy:
        standard:
          shardingColumn: account_id
          shardingAlgorithmName: account-id-hash
      keyGenerateStrategy:
        column: id
        keyGeneratorName: snowflake

  shardingAlgorithms:
    account-id-hash:
      type: INLINE
      props:
        algorithm-expression: journal_line_${Math.abs(account_id.hashCode() % 4)}

  keyGenerators:
    snowflake:
      type: SNOWFLAKE
      props:
        worker-id: 0

props:
  sql-show: true
```

#### Key design points

1. **Single data source (`ds_0`)** — sharding is table-level, not database-level. If the MySQL instance becomes a bottleneck, upgrade to a larger instance or switch to database-level sharding (`ds_0`, `ds_1`, …) without changing application code.
2. **`!SINGLE` rule** — all tables except `journal_line` are treated as broadcast/single tables. ShardingSphere routes them to the default data source without rewriting SQL.
3. **Snowflake ID** — the `id` column is auto-generated by ShardingSphere's built-in SNOWFLAKE algorithm. Each projection instance must set a unique `worker-id` (via env `SNOWFLAKE_WORKER_ID`, range 0–1023) to avoid ID collisions.
4. **Logical table abstraction** — application code (MyBatis mappers) references `journal_line`; ShardingSphere rewrites the SQL to the correct physical table at runtime.

#### Activation

```bash
# Spring Boot profile
--spring.profiles.active=sharding

# Per-instance worker ID (required when running multiple projection consumers)
export SNOWFLAKE_WORKER_ID=1
```

#### Operational prerequisites

- Physical tables `journal_line_0` through `journal_line_{N-1}` must be pre-created (see `init.sql`)
- Schema changes on `journal_line` must be applied to **all** physical tables
- Re-sharding (changing from 4 to 8 shards) requires a data migration; plan for it before production

---

### 6.4 Data Guarantee

```
RocksDB: Strong durability, the single source of truth for ledger data
MySQL: Eventually consistent query view, can be rebuilt from RocksDB replay
```

---

## 7. Technical Constraints

| Constraint | Description |
|---|---|
| All ledger write operations must go through Raft | Direct writes to MySQL bypassing Raft are prohibited |
| Balance queries must read in-memory State Machine | Reading MySQL balance (may be stale) is prohibited |
| Account Worker must be single-threaded serial | Concurrent execution of multiple journals on the same account is prohibited |
| Multi-account tasks must be sorted by accountId ascending | Prevents deadlock; enforced on all paths |
| No ORM | Hibernate / JPA prohibited; RocksDB uses RocksDB Java API, MySQL uses MyBatis |
| State Machine Snapshot interval ≤ 100,000 Raft Logs | Controls failure recovery time |

---

## 8. Risks and Mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| Leader single-point throughput limit | Medium | Multi-Raft-Group horizontal scaling by account grouping |
| State Machine out of memory | Medium | Only keep active account balances; cold accounts loaded on-demand from RocksDB |
| Learner sync delay causing Journal query inconsistency | Low | Label query timestamp, indicate "eventual consistency" |
| RocksDB corruption | Low | Multi-replica Raft; any Follower can be used for data recovery |
| SOFAJRaft version maintenance risk | Low | Evaluate Apache Ratis as alternative |
| **Multi-Account readyLatch timeout (Queue backlog)** | Medium | Single-account Queue capacity ≤ 1000, exceeding returns 429 fast-fail; 30ms readyLatch timeout prevents infinite waiting; caller retries with requestId |
| **Leader Worker Raft timeout (failure / network partition)** | Medium | 100ms resultLatch timeout; caller retries with requestId; idempotency guarantees no duplicate posting |

---

## 9. Alternative Solutions Considered

| Solution | Reason for Rejection |
|---|---|
| Traditional Spring Boot + PostgreSQL row lock | P95 cannot be met under hotspot accounts |
| Shard + Rebalancing | Fund management complexity; rebalancing introduces fund gap risk |
| Pre-authorized Limit + async Settlement | Does not meet "synchronous atomic posting" requirement |
| Redis as balance cache | Consistency risk unacceptable (financial scenario) |
| LMAX Disruptor | Solves thread messaging delay, not Raft network round-trip; busy-spin model incompatible with per-account millions of queues |
| `synchronized` / `ReentrantLock` account locks | OS thread block during lock hold wastes CPU; Virtual Thread park/unpark with Queue natural serialization requires no explicit locks |

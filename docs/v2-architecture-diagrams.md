# jraft_ledger v2 — Architecture Diagrams

## 1. System Context

```
                          External Consumers
                   (RFQ Engine, Withdrawal, Admin UI)
                                │
                   Writes       │       Reads
                (leader only)   │    (any node)
                                │
        ┌───────────────────────┼──────────────────────────┐
        │                       ▼                          │
        │   ┌─────────────────────────────────────────┐    │
        │   │         Raft Cluster (3 nodes)           │    │
        │   │                                          │    │
        │   │  ┌────────────┐┌────────────┐┌────────────┐  │
        │   │  │  Node-1    ││  Node-2    ││  Node-3    │  │
        │   │  │  :8081     ││  :8082     ││  :8083     │  │
        │   │  │  Raft:28081││  Raft:28082││  Raft:28083│  │
        │   │  │            ││            ││            │  │
        │   │  │ StateMach  ││ StateMach  ││ StateMach  │  │
        │   │  │ (in-mem)   ││ (in-mem)   ││ (in-mem)   │  │
        │   │  │     │      ││            ││            │  │
        │   │  │ RocksDB    ││ RocksDB    ││ RocksDB    │  │
        │   │  │ (snapshot) ││ (snapshot) ││ (snapshot) │  │
        │   │  └────────────┘└────────────┘└────────────┘  │
        │   │         │   Bolt RPC replication   │         │
        │   │         └──────────┬───────────────┘         │
        │   └────────────────────┼─────────────────────────┘
        │                        │
        │                        ▼
        │   ┌──────────────────────────────────────────┐
        │   │            Kafka (KRaft mode)             │
        │   │                                           │
        │   │  ledger.balance.change.v1   (per-line)    │
        │   │  ledger.account.v1          (per-account) │
        │   │                                           │
        │   │  Key: accountId:balanceType:currency      │
        │   │  64 partitions, LZ4, acks=all             │
        │   └─────────────────┬─────────────────────────┘
        │                     │
        │                     ▼ async consume
        │   ┌──────────────────────────────────────────┐
        │   │       Projection Service (:8089)          │
        │   │                                           │
        │   │  KafkaListener → MyBatis + ShardingSphere │
        │   │  Stateless, horizontally scalable         │
        │   └─────────────────┬─────────────────────────┘
        │                     │
        │                     ▼
        │   ┌──────────────────────────────────────────┐
        │   │           MySQL 8.4 (:3306)               │
        │   │           database: ledger_view            │
        │   │                                           │
        │   │  journal, journal_line, account,          │
        │   │  account_balance, balance_type_registry   │
        │   │                                           │
        │   │  Read-only view layer for:                │
        │   │    Journal Query, Reconciliation           │
        │   │  NOT for: Balance Query (in-memory)       │
        │   └──────────────────────────────────────────┘
        └──────────────────────────────────────────────────┘
```

---

## 2. Single-Node Internal Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                        Ledger Node (Spring Boot)                      │
│                                                                       │
│  ┌─── REST Layer ───────────────────────────────────────────────────┐ │
│  │                                                                   │ │
│  │  PostingController          POST /ledger/postings                │ │
│  │  ReversalController         POST /ledger/journals/{id}/reversal  │ │
│  │  AdjustmentController       POST /ledger/adjustments/drafts      │ │
│  │  AccountController          POST /ledger/accounts                │ │
│  │  BalanceQueryController     GET  /ledger/accounts/{id}/balances  │ │
│  │  ReconciliationController   POST /ledger/reconciliation/trigger  │ │
│  │  ClusterController          GET  /ledger/cluster/status          │ │
│  │  HealthController           GET  /actuator/health                │ │
│  │                                                                   │ │
│  │  GlobalExceptionHandler     (unified error responses)            │ │
│  └───────────────────────────────────┬───────────────────────────────┘ │
│                                      │                                 │
│  ┌─── Service Layer ────────────────┼───────────────────────────────┐ │
│  │                                   │                               │ │
│  │  PostingService ─────────────────┤                               │ │
│  │  ReversalService ────────────────┤                               │ │
│  │  AdjustmentService ─────────────┤  (thin wrappers)              │ │
│  │  AccountService ────────────────┤                               │ │
│  │  BalanceQueryService ───────────┤                               │ │
│  │  BalanceTypeConfigService ──────┤                               │ │
│  │  AccountingPeriodService ───────┤                               │ │
│  │  ReconciliationService ─────────┤                               │ │
│  │  JournalQueryService ───────────┘                               │ │
│  └───────────────────────────────────┬───────────────────────────────┘ │
│                                      │                                 │
│  ┌─── Raft Layer ───────────────────┼───────────────────────────────┐ │
│  │                                   ▼                               │ │
│  │  ┌────────────────────────────────────────┐                      │ │
│  │  │         RaftNodeManager                 │                      │ │
│  │  │                                         │                      │ │
│  │  │  submit(RaftCommand)                    │                      │ │
│  │  │    → serialize (CommandSerializer)      │                      │ │
│  │  │    → create Raft Task                   │                      │ │
│  │  │    → register CompletableFuture         │                      │ │
│  │  │    → block up to 30s                    │                      │ │
│  │  │                                         │                      │ │
│  │  │  pendingCommands: ConcurrentHashMap     │                      │ │
│  │  │    <requestId, CompletableFuture>       │                      │ │
│  │  │                                         │                      │ │
│  │  │  Config:                                │                      │ │
│  │  │    election-timeout: 1000ms             │                      │ │
│  │  │    snapshot-interval: 3600s             │                      │ │
│  │  │    group-id: ledger-group-1             │                      │ │
│  │  └─────────────────┬──────────────────────┘                      │ │
│  │                    │ SOFAJRaft replicates to quorum               │ │
│  │                    ▼                                              │ │
│  │  ┌────────────────────────────────────────┐                      │ │
│  │  │    LedgerRaftStateMachine               │                      │ │
│  │  │    (extends StateMachineAdapter)         │                      │ │
│  │  │                                         │                      │ │
│  │  │  onApply(iterator):                     │                      │ │
│  │  │    deserialize → dispatch by type:      │                      │ │
│  │  │      PostingCommand  → applyPosting()   │                      │ │
│  │  │      ReversalCommand → applyReversal()  │                      │ │
│  │  │      AccountCreate   → applyCreate()    │                      │ │
│  │  │      AccountFreeze   → applyFreeze()    │                      │ │
│  │  │      AccountClose    → applyClose()     │                      │ │
│  │  │      AddBalanceType  → applyAddBT()     │                      │ │
│  │  │    complete CompletableFuture           │                      │ │
│  │  │                                         │                      │ │
│  │  │  onSnapshotSave():                      │                      │ │
│  │  │    serialize → file + RocksDB           │                      │ │
│  │  │  onSnapshotLoad():                      │                      │ │
│  │  │    leader transfer → fallback RocksDB   │                      │ │
│  │  │                                         │                      │ │
│  │  │  ThreadLocal reuse buffer (16KB)        │                      │ │
│  │  └─────────────────┬──────────────────────┘                      │ │
│  │                    │ NodeRole tracking                            │ │
│  │                    │ (LEADER / FOLLOWER / CANDIDATE)              │ │
│  └────────────────────┼─────────────────────────────────────────────┘ │
│                       │                                               │
│  ┌─── Core Engine ────┼─────────────────────────────────────────────┐ │
│  │                    ▼                                              │ │
│  │  ┌────────────────────────────────────────────────────────────┐  │ │
│  │  │              LedgerStateMachine (664 lines)                  │  │ │
│  │  │              synchronized — single-threaded apply            │  │ │
│  │  │                                                              │  │ │
│  │  │  ┌──────────┐ ┌──────────────┐ ┌───────────────────────┐   │  │ │
│  │  │  │ Balance   │ │ AccountMeta  │ │ BalanceTypeConfig     │   │  │ │
│  │  │  │ Store     │ │ Store        │ │ Store                 │   │  │ │
│  │  │  │           │ │              │ │                       │   │  │ │
│  │  │  │ HashMap   │ │ HashMap      │ │ HashMap               │   │  │ │
│  │  │  │ <key,     │ │ <accountId,  │ │ <typeCode,            │   │  │ │
│  │  │  │  Balance  │ │  Account>    │ │  BalanceTypeConfig>   │   │  │ │
│  │  │  │  Entry>   │ │              │ │                       │   │  │ │
│  │  │  └──────────┘ └──────────────┘ └───────────────────────┘   │  │ │
│  │  │                                                              │  │ │
│  │  │  ┌──────────┐ ┌──────────────────────────────────────────┐ │  │ │
│  │  │  │Idempot.  │ │ AccountQueueManager                      │ │  │ │
│  │  │  │Store     │ │   per-account LinkedBlockingQueue         │ │  │ │
│  │  │  │          │ │   Virtual Thread worker per account       │ │  │ │
│  │  │  │ HashMap  │ │   MAX_QUEUE_SIZE = 1000                  │ │  │ │
│  │  │  │ <reqId,  │ │   Multi-account: sorted lock order       │ │  │ │
│  │  │  │  Entry>  │ │   Backpressure: reject if full           │ │  │ │
│  │  │  └──────────┘ └──────────────────────────────────────────┘ │  │ │
│  │  │                                                              │  │ │
│  │  │  Key invariants:                                            │  │ │
│  │  │    - accountSeq++ per (accountId, balanceType, currency)    │  │ │
│  │  │    - balanceBefore/After on every JournalLine               │  │ │
│  │  │    - configVersion snapshot per line                        │  │ │
│  │  │    - Seed ops restricted to COMPANY/NOSTRO/SUSPENSE         │  │ │
│  │  │    - Reusable collections (zero-alloc hot path)             │  │ │
│  │  │    - overflow warn at 80% Long.MAX_VALUE                    │  │ │
│  │  └────────────────────────────────┬───────────────────────────┘  │ │
│  │                                   │                               │ │
│  │                          events   │   snapshot                    │ │
│  │                     ┌─────────────┼──────────┐                   │ │
│  │                     ▼             ▼          ▼                   │ │
│  │  ┌──────────────────────┐ ┌───────────────────────────────────┐ │ │
│  │  │ KafkaEventPublisher  │ │          RocksDB                   │ │ │
│  │  │                      │ │                                    │ │ │
│  │  │ onEvent():           │ │  9 Column Families:                │ │ │
│  │  │  BalanceChangeEvent  │ │  ┌─────────────────────────────┐  │ │ │
│  │  │  → balance.change.v1 │ │  │ default                     │  │ │ │
│  │  │                      │ │  │ journal      → Journal JSON  │  │ │ │
│  │  │ onAccountCreated():  │ │  │ journal_line → JLine JSON    │  │ │ │
│  │  │  AccountCreatedEvent │ │  │ balance      → BalEntry JSON │  │ │ │
│  │  │  → account.v1        │ │  │ idempotency  → Idemp. JSON   │  │ │ │
│  │  │                      │ │  │ account_meta → Account JSON  │  │ │ │
│  │  │ Config: acks=all,    │ │  │ balance_type → BTConf JSON   │  │ │ │
│  │  │ lz4 compression      │ │  │ sm_snapshot  → full snapshot │  │ │ │
│  │  └──────────┬───────────┘ │  │ outbox       → Event JSON    │  │ │ │
│  │             │             │  └─────────────────────────────┘  │ │ │
│  │             │             │                                    │ │ │
│  │             │             │  OutboxStore (at-least-once):      │ │ │
│  │             │             │    enqueue() → flush() → CF_OUTBOX│ │ │
│  │             │             │    AsyncKafkaPublisher reads       │ │ │
│  │             │             └───────────────────────────────────┘ │ │
│  │             │                                                    │ │
│  └─────────────┼────────────────────────────────────────────────────┘ │
│                │                                                       │
└────────────────┼───────────────────────────────────────────────────────┘
                 │
                 ▼ Kafka
```

---

## 3. Write Path — Posting (Sequence)

```
Client              PostingController    RaftNodeManager     SOFAJRaft        LedgerRaftSM        LedgerStateMachine    Kafka           OutboxStore
  │                       │                   │                  │                 │                    │                  │                 │
  │  POST /postings       │                   │                  │                 │                    │                  │                 │
  │──────────────────────►│                   │                  │                 │                    │                  │                 │
  │                       │                   │                  │                 │                    │                  │                 │
  │                       │ isLeader()?       │                  │                 │                    │                  │                 │
  │                       │──────────────────►│                  │                 │                    │                  │                 │
  │                       │◄── true ──────────│                  │                 │                    │                  │                 │
  │                       │                   │                  │                 │                    │                  │                 │
  │                       │ submit(PostingCmd)│                  │                 │                    │                  │                 │
  │                       │──────────────────►│                  │                 │                    │                  │                 │
  │                       │                   │                  │                 │                    │                  │                 │
  │                       │                   │ serialize cmd    │                 │                    │                  │                 │
  │                       │                   │ register Future  │                 │                    │                  │                 │
  │                       │                   │ create Raft Task │                 │                    │                  │                 │
  │                       │                   │─────────────────►│                 │                    │                  │                 │
  │                       │                   │                  │                 │                    │                  │                 │
  │                       │                   │                  │  replicate to   │                    │                  │                 │
  │                       │                   │                  │  quorum (2/3)   │                    │                  │                 │
  │                       │                   │                  │  via Bolt RPC   │                    │                  │                 │
  │                       │                   │                  │                 │                    │                  │                 │
  │                       │                   │                  │  committed      │                    │                  │                 │
  │                       │                   │                  │────────────────►│                    │                  │                 │
  │                       │                   │                  │                 │                    │                  │                 │
  │                       │                   │                  │                 │ onApply()          │                  │                 │
  │                       │                   │                  │                 │ deserialize cmd    │                  │                 │
  │                       │                   │                  │                 │ dispatch by type   │                  │                 │
  │                       │                   │                  │                 │                    │                  │                 │
  │                       │                   │                  │                 │ applyPosting(cmd)  │                  │                 │
  │                       │                   │                  │                 │───────────────────►│                  │                 │
  │                       │                   │                  │                 │                    │                  │                 │
  │                       │                   │                  │                 │                    │── 1. idempotency │                 │
  │                       │                   │                  │                 │                    │── 2. acct status │                 │
  │                       │                   │                  │                 │                    │── 3. leg balance │                 │
  │                       │                   │                  │                 │                    │── 4. floor/ceil  │                 │
  │                       │                   │                  │                 │                    │── 5. gen journal │                 │
  │                       │                   │                  │                 │                    │── 6. update bal  │                 │
  │                       │                   │                  │                 │                    │      (seq++)     │                 │
  │                       │                   │                  │                 │                    │                  │                 │
  │                       │                   │                  │                 │                    │── 7. publish ───►│ onEvent()       │
  │                       │                   │                  │                 │                    │                  │ → Kafka topic   │
  │                       │                   │                  │                 │                    │                  │                 │
  │                       │                   │                  │                 │                    │── 8. enqueue ──────────────────────►│
  │                       │                   │                  │                 │                    │                  │                 │
  │                       │                   │                  │                 │                    │── 9. idempotency │                 │
  │                       │                   │                  │                 │                    │      record      │                 │
  │                       │                   │                  │                 │                    │                  │                 │
  │                       │                   │                  │                 │                    │── 10. snapshot   │                 │
  │                       │                   │                  │                 │                    │       → RocksDB  │                 │
  │                       │                   │                  │                 │                    │                  │                 │
  │                       │                   │                  │                 │◄── CommandResult ──│                  │                 │
  │                       │                   │                  │                 │                    │                  │                 │
  │                       │                   │                  │                 │ complete Future    │                  │                 │
  │                       │                   │◄────────────────────────────────────                   │                  │                 │
  │                       │                   │                  │                 │                    │                  │                 │
  │                       │◄── CommandResult ─│                  │                 │                    │                  │                 │
  │                       │                   │                  │                 │                    │                  │                 │
  │◄── 200 OK ────────────│                   │                  │                 │                    │                  │                 │
  │    { journalId }      │                   │                  │                 │                    │                  │                 │
```

---

## 4. Read Paths

```
═══════════════════════════════════════════════════════════════════════
 Balance Query (real-time) — ANY NODE, NO DB, <2ms
═══════════════════════════════════════════════════════════════════════

  Client ──► BalanceQueryController ──► BalanceQueryService
                                              │
                                              ▼
                                     LedgerStateMachine
                                      .balanceStore
                                      .get(accountId,
                                           balanceType,
                                           currency)
                                              │
                                              ▼
                                     BalanceEntry {
                                       amount: BigDecimal
                                       accountSeq: long
                                       lastJournalId: String
                                       lastUpdatedAt: Instant
                                     }

═══════════════════════════════════════════════════════════════════════
 Journal Query (historical) — ANY NODE, MySQL, <30ms
═══════════════════════════════════════════════════════════════════════

  Client ──► Projection Service (:8089)
                     │
                     ▼
             ProjectionQueryController
                     │
                     ▼
              MySQL (ledger_view)
              ┌─────────────────┐     ┌──────────────────────┐
              │    journal       │────►│    journal_line       │
              │                  │     │                       │
              │  journal_id (PK) │     │  journal_line_id (PK) │
              │  journal_type    │     │  journal_id (FK)      │
              │  request_id      │     │  leg_id               │
              │  value_date      │     │  account_id           │
              │  status          │     │  balance_type         │
              │  cross_period    │     │  currency             │
              └─────────────────┘     │  entry_type           │
                                      │  amount               │
              ┌─────────────────┐     │  balance_before       │
              │    account       │     │  balance_after        │
              │                  │     │  config_version       │
              │  account_id (PK) │     └──────────────────────┘
              │  account_type    │
              │  status          │     ┌──────────────────────┐
              └─────────────────┘     │  account_balance      │
                                      │                       │
                                      │  account_id           │
                                      │  balance_type         │
                                      │  currency             │
                                      │  balance (amount)     │
                                      │  account_seq          │
                                      └──────────────────────┘
```

---

## 5. Raft Consensus & Replication

```
                    ┌─────────────────────────┐
                    │  Client writes to Leader │
                    └────────────┬────────────┘
                                 │
                                 ▼
┌───────────────────────────────────────────────────────────────────┐
│                         Raft Group: ledger-group-1                 │
│                                                                    │
│   Node-1 (LEADER)         Node-2 (FOLLOWER)    Node-3 (FOLLOWER)  │
│   :8081 / :28081          :8082 / :28082       :8083 / :28083     │
│                                                                    │
│   ┌──────────────┐        ┌──────────────┐     ┌──────────────┐   │
│   │  Raft Log     │──────►│  Raft Log     │     │  Raft Log     │  │
│   │  [1][2][3][4] │  Bolt │  [1][2][3][4] │     │  [1][2][3]   │  │
│   └──────┬───────┘  RPC  └──────┬───────┘     └──────┬───────┘   │
│          │                      │                     │            │
│          ▼                      ▼                     ▼            │
│   ┌──────────────┐        ┌──────────────┐     ┌──────────────┐   │
│   │ StateMachine  │        │ StateMachine  │     │ StateMachine  │  │
│   │ (all state)   │        │ (all state)   │     │ (all state)   │  │
│   └──────┬───────┘        └──────────────┘     └──────────────┘   │
│          │                                                         │
│          ▼                                                         │
│   ┌──────────────┐                                                │
│   │ Kafka Publish │   (only leader publishes events)              │
│   │ OutboxStore   │                                                │
│   └──────────────┘                                                │
│                                                                    │
│   Election: timeout 1000ms                                        │
│   Commit: quorum (2 of 3 nodes)                                   │
│   Snapshot: every 3600s → RocksDB sm_snapshot CF                  │
│                                                                    │
│   ┌──────────────────────────────────────────────────────────┐    │
│   │  Leader Election Timeline                                 │    │
│   │                                                           │    │
│   │  Node-1 ████████████ LEADER ████████████████████████      │    │
│   │  Node-2 ░░░░░░░░░░░ FOLLOWER ░░░░░░░░░░░░░░░░░░░░       │    │
│   │  Node-3 ░░░░░░░░░░░ FOLLOWER ░░░░░░░░░░░░░░░░░░░░       │    │
│   │                                                           │    │
│   │  If Node-1 crashes:                                       │    │
│   │  Node-1 ████████ DEAD ░░░░░░░░░░░░░░░░░░░░░░░░░░░       │    │
│   │  Node-2 ░░░░░░░░░░░░░ CANDIDATE → LEADER █████████       │    │
│   │  Node-3 ░░░░░░░░░░░░░ FOLLOWER ░░░░░░░░░░░░░░░░░░       │    │
│   │                        ▲                                  │    │
│   │                        │ ~1-3 seconds                     │    │
│   └──────────────────────────────────────────────────────────┘    │
└───────────────────────────────────────────────────────────────────┘
```

---

## 6. Module Dependency Graph

```
┌─────────────────────────────────────────────────────────────────┐
│                                                                  │
│                   ledger-core (foundation)                        │
│                                                                  │
│  ┌─────────────┐ ┌──────────────┐ ┌───────────────────────────┐ │
│  │ statemachine │ │    raft       │ │        rocksdb             │ │
│  │              │ │              │ │                            │ │
│  │ LedgerState  │ │ RaftNode     │ │ RocksDBManager            │ │
│  │  Machine     │ │  Manager     │ │ ColumnFamilyRegistry      │ │
│  │              │ │ LedgerRaft   │ │ OutboxStore               │ │
│  │              │ │  StateMachine│ │ RocksDBKeySerializer      │ │
│  │              │ │ CommandSeri  │ │                            │ │
│  │              │ │  alizer     │ │                            │ │
│  │              │ │ NodeRole     │ │                            │ │
│  └──────────────┘ └──────────────┘ └───────────────────────────┘ │
│  ┌─────────────┐ ┌──────────────┐ ┌───────────────────────────┐ │
│  │   store      │ │   queue      │ │        event              │ │
│  │              │ │              │ │                            │ │
│  │ BalanceStore │ │ AccountQueue │ │ KafkaEventPublisher       │ │
│  │ AccountMeta  │ │  Manager     │ │ LedgerEventListener       │ │
│  │  Store       │ │              │ │                            │ │
│  │ BalanceType  │ │              │ │                            │ │
│  │  ConfigStore │ │              │ │                            │ │
│  │ Idempotency  │ │              │ │                            │ │
│  │  Store       │ │              │ │                            │ │
│  └──────────────┘ └──────────────┘ └───────────────────────────┘ │
│  ┌──────────────────────────────────────────────────────────────┐│
│  │                     domain / model                            ││
│  │                                                               ││
│  │  PostingCommand, ReversalCommand, AccountCreateCommand        ││
│  │  Journal, JournalLine, BalanceEntry, Account                  ││
│  │  BalanceTypeConfig, BalanceChangeEvent, AccountCreatedEvent    ││
│  │  CommandResult, SnapshotData, RaftCommand                     ││
│  │  AccountType, AccountStatus, EntryType, JournalType/Status    ││
│  │  AccountBalanceKey, IdempotencyEntry                          ││
│  └──────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────┬───────────────────────────┘
                                      │
                        ┌─────────────┼─────────────┐
                        │             │             │
                        ▼             │             ▼
            ┌───────────────┐        │   ┌───────────────────┐
            │  ledger-dao    │        │   │  ledger-feign      │
            │                │        │   │                    │
            │ MyBatis        │        │   │ OpenFeign clients  │
            │  Mappers:      │        │   │  (for external     │
            │  JournalMapper │        │   │   service calls)   │
            │  AccountMapper │        │   │                    │
            │  AccountBal.   │        │   └───────────────────┘
            │   Mapper       │        │
            │  BalTypeReg.   │        │
            │   Mapper       │        │
            └───────┬───────┘        │
                    │                │
                    ▼                ▼
            ┌──────────────────────────────────────┐
            │          ledger-service                │
            │                                       │
            │  PostingService                       │
            │  ReversalService                      │
            │  AdjustmentService                    │
            │  AccountService                       │
            │  BalanceQueryService                   │
            │  BalanceTypeConfigService              │
            │  AccountingPeriodService               │
            │  ReconciliationService                 │
            │  JournalQueryService                   │
            └──────────────────┬───────────────────┘
                               │
                               ▼
            ┌──────────────────────────────────────┐
            │          ledger-restful                │
            │          (Spring Boot app)             │
            │                                       │
            │  PostingController                    │
            │  ReversalController                   │
            │  AdjustmentController                 │
            │  AccountController                    │
            │  BalanceQueryController                │
            │  ReconciliationController              │
            │  ClusterController                    │
            │  HealthController                     │
            │  GlobalExceptionHandler               │
            │  LedgerConfig                         │
            └──────────────────────────────────────┘


            ┌──────────────────────────────────────┐
            │       ledger-projection               │
            │       (separate Spring Boot app)       │
            │                                       │
            │  ProjectionConsumer                   │
            │    @KafkaListener:                     │
            │      balance.change.v1 → MySQL         │
            │      account.v1        → MySQL         │
            │                                       │
            │  ProjectionQueryController             │
            │  ProjectionConfig                     │
            │                                       │
            │  Depends on: ledger-core + ledger-dao  │
            └──────────────────────────────────────┘
```

---

## 7. Domain Model — Entity Relationships

```
┌──────────────────────────────────────────────────────────────────────┐
│                         Domain Model (In-Memory)                      │
│                                                                       │
│  ┌─────────────────┐         ┌──────────────────────────────────┐    │
│  │    Account        │         │         BalanceTypeConfig         │    │
│  │                   │         │                                   │    │
│  │ accountId (PK)    │         │ typeCode (PK)                    │    │
│  │ accountType:      │         │ allowNegative: boolean           │    │
│  │   CLIENT          │         │ negativeSemantics: String        │    │
│  │   COMPANY         │         │ signConvention: NORMAL/INVERTED  │    │
│  │   SUSPENSE        │         │ configVersion: int               │    │
│  │   NOSTRO          │         │                                   │    │
│  │   CONTROL         │         │ Examples:                        │    │
│  │ status:           │         │   LIQUID (neg=false)             │    │
│  │   ACTIVE          │         │   PENDING_INCOMING (neg=false)   │    │
│  │   FROZEN          │         │   PENDING_OUTGOING (neg=false)   │    │
│  │   CLOSED          │         │   COMPLIANCE_HOLD (neg=false)    │    │
│  │ allowedBalance    │         │   LIABILITY (neg=true)           │    │
│  │   Types: [...]    │         └──────────────────────────────────┘    │
│  └────────┬──────────┘                                                │
│           │ 1:N                                                       │
│           ▼                                                           │
│  ┌──────────────────────────────────────────────┐                    │
│  │              BalanceEntry                      │                    │
│  │                                                │                    │
│  │  Key: (accountId, balanceType, currency)       │                    │
│  │                                                │                    │
│  │  amount: BigDecimal                            │                    │
│  │  stateVersion: long                            │                    │
│  │  accountSeq: long  ← monotonic, per-key        │                    │
│  │  lastJournalId: String                         │                    │
│  │  lastUpdatedAt: Instant                        │                    │
│  └──────────────────────────────────────────────┘                    │
│                                                                       │
│  ┌──────────────────────────────────────────────────────────────┐    │
│  │                          Journal                               │    │
│  │                                                                │    │
│  │  journalId: "JNL-{seq}"                                      │    │
│  │  journalType: NORMAL | REVERSAL | MANUAL_ADJUSTMENT           │    │
│  │  status: CONFIRMED | PENDING | PROVISIONAL | REVERSED         │    │
│  │  requestId: UUID v7 (idempotency key)                         │    │
│  │  businessEventRef: String (external correlation)              │    │
│  │  valueDate: LocalDate                                         │    │
│  │  crossPeriod: boolean                                         │    │
│  │  createdAt: Instant                                           │    │
│  │       │                                                        │    │
│  │       │ 1:N                                                    │    │
│  │       ▼                                                        │    │
│  │  ┌────────────────────────────────────────────────────────┐   │    │
│  │  │                     JournalLine                         │   │    │
│  │  │                                                         │   │    │
│  │  │  journalLineId: "{journalId}-{seq}"                    │   │    │
│  │  │  legId: String (groups balanced debit/credit pairs)    │   │    │
│  │  │  accountId: String                                     │   │    │
│  │  │  balanceType: String (LIQUID, PENDING_INCOMING, etc.)  │   │    │
│  │  │  currency: String                                      │   │    │
│  │  │  entryType: DEBIT | CREDIT                             │   │    │
│  │  │  amount: BigDecimal                                    │   │    │
│  │  │  balanceBefore: BigDecimal                             │   │    │
│  │  │  balanceAfter: BigDecimal                              │   │    │
│  │  │  configVersion: int  (snapshot of BalanceTypeConfig)   │   │    │
│  │  └────────────────────────────────────────────────────────┘   │    │
│  └──────────────────────────────────────────────────────────────┘    │
│                                                                       │
│  ┌──────────────────────────────────────────────────────────────┐    │
│  │                    BalanceChangeEvent (v1.1)                    │    │
│  │                                                                │    │
│  │  eventId, journalId, journalLineId                            │    │
│  │  accountId, balanceType, currency                             │    │
│  │  entryType, amount                                            │    │
│  │  balanceBefore, balanceAfter                                  │    │
│  │  accountSeq, prevAccountSeq                                   │    │
│  │  raftLogIndex                                                 │    │
│  │  valueDate, timestamp                                         │    │
│  │                                                                │    │
│  │  Kafka Key: accountId:balanceType:currency                    │    │
│  │  (guarantees per-account ordering within partition)            │    │
│  └──────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 8. RocksDB Storage Layout

```
/var/lib/ledger/rocksdb/  (or ./jraft_ledger/node{N})
│
├── Column Family: default
│
├── Column Family: journal
│   Key:   journalId (String)
│   Value: Journal JSON
│   Example: "JNL-0001" → {"journalId":"JNL-0001","journalType":"NORMAL",...}
│
├── Column Family: journal_line
│   Key:   journalLineId (String)
│   Value: JournalLine JSON
│   Example: "JNL-0001-01" → {"accountId":"ACC-001","entryType":"DEBIT",...}
│
├── Column Family: balance
│   Key:   accountId:balanceType:currency (serialized by RocksDBKeySerializer)
│   Value: BalanceEntry JSON
│   Example: "ACC-001:LIQUID:USD" → {"amount":"1000.00","accountSeq":42,...}
│
├── Column Family: idempotency
│   Key:   requestId (UUID v7)
│   Value: IdempotencyEntry JSON
│   Example: "019..." → {"status":"COMPLETED","journalId":"JNL-0001",...}
│
├── Column Family: account_meta
│   Key:   accountId
│   Value: Account JSON
│   Example: "ACC-001" → {"accountType":"CLIENT","status":"ACTIVE",...}
│
├── Column Family: balance_type
│   Key:   typeCode
│   Value: BalanceTypeConfig JSON
│   Example: "LIQUID" → {"allowNegative":false,"configVersion":1,...}
│
├── Column Family: sm_snapshot
│   Key:   "snapshot"
│   Value: SnapshotData JSON (complete state machine state)
│         {
│           balances: { ... },
│           accounts: { ... },
│           balanceTypeConfigs: { ... },
│           journals: { ... },
│           idempotencyEntries: { ... },
│           raftLogIndex: 1234,
│           journalSequence: 42
│         }
│
└── Column Family: outbox
    Key:   "outbox:{eventId}"
    Value: BalanceChangeEvent JSON (pending Kafka delivery)
```

---

## 9. Docker Compose Deployment

```
┌─────────────────────────────────────────────────────────────────────┐
│                    docker-compose (ledger-net)                        │
│                                                                      │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │  ledger-node-1          ledger-node-2          ledger-node-3  │  │
│  │  :8081 (HTTP)           :8082 (HTTP)           :8083 (HTTP)   │  │
│  │  :28081 (Raft RPC)      :28082 (Raft RPC)      :28083 (RPC)  │  │
│  │                                                                │  │
│  │  RAFT_PEERS=ledger-node-1:28081,                              │  │
│  │             ledger-node-2:28082,                               │  │
│  │             ledger-node-3:28083                                │  │
│  │                                                                │  │
│  │  Volumes:                                                      │  │
│  │    ./jraft_ledger/node1 → /var/lib/ledger/rocksdb              │  │
│  │    ./jraft_ledger/node1/raft → /var/lib/ledger/raft            │  │
│  └───────────────────────────────────────────────────────────────┘  │
│                                                                      │
│  ┌───────────────────────┐  ┌───────────────────────────────────┐  │
│  │  ledger-kafka          │  │  ledger-mysql                     │  │
│  │  :9092                 │  │  :3306                            │  │
│  │  KRaft mode            │  │  MySQL 8.4                        │  │
│  │  (no ZooKeeper)        │  │  database: ledger_view            │  │
│  │                        │  │                                    │  │
│  │  Topics:               │  │  Tables:                          │  │
│  │   balance.change.v1    │  │   journal                         │  │
│  │   account.v1           │  │   journal_line                    │  │
│  │                        │  │   account                         │  │
│  │  Volume:               │  │   account_balance                 │  │
│  │   ./jraft_ledger/kafka │  │   balance_type_registry           │  │
│  └───────────────────────┘  │                                    │  │
│                              │  Volume:                           │  │
│  ┌───────────────────────┐  │   ./jraft_ledger/mysql             │  │
│  │  ledger-projection     │  └───────────────────────────────────┘  │
│  │  :8089                 │                                         │
│  │                        │  ┌───────────────────────────────────┐  │
│  │  Kafka Consumer        │  │  kafka-ui                         │  │
│  │  → MySQL projection    │  │  :8080                            │  │
│  │  ShardingSphere        │  │  (dev/debug only)                 │  │
│  └───────────────────────┘  └───────────────────────────────────┘  │
│                                                                      │
│  Network: ledger-net (bridge)                                       │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 10. Recovery & Snapshot Flow

```
═══════════════════════════════════════════════════════════════
  NORMAL OPERATION — Snapshot every 3600s
═══════════════════════════════════════════════════════════════

  Every apply():
    LedgerStateMachine state updated (in-memory)
         │
         ▼
    takeSnapshot() → serialize full state → RocksDB CF_SM_SNAPSHOT
         │
         ▼
    Raft log grows: [entry1][entry2][entry3]...[entryN]

  Every 3600s (configurable):
    LedgerRaftStateMachine.onSnapshotSave()
         │
         ├──► Write state bytes to Raft snapshot directory
         │    (for leader-to-follower transfer)
         │
         └──► Write to RocksDB sm_snapshot CF
              (for local fast recovery)
         │
         ▼
    Raft log compacted: entries before snapshot index are pruned


═══════════════════════════════════════════════════════════════
  RECOVERY — Node restart
═══════════════════════════════════════════════════════════════

  App Start
      │
      ▼
  RocksDBManager.open(dataPath)
      │  Opens all 9 Column Families
      │
      ▼
  LedgerRaftStateMachine.onSnapshotLoad()
      │
      ├── Option A: Leader sends snapshot (new node joining)
      │   → Deserialize from transferred bytes
      │
      └── Option B: Local RocksDB (normal restart)
          → Read CF_SM_SNAPSHOT → deserialize SnapshotData
      │
      ▼
  Restore in-memory stores:
      balanceStore         (all balance entries with accountSeq)
      accountMetaStore     (all accounts with status)
      balanceTypeConfigStore (all balance type configs)
      journalStore         (all journals — optional, can be large)
      idempotencyStore     (recent request IDs)
      raftLogIndex         (last applied log index)
      journalSequence      (next journal number)
      │
      ▼
  SOFAJRaft replays log entries AFTER snapshot index
      │  [snapshot_index + 1] → [latest_committed]
      │  Each entry → onApply() → LedgerStateMachine
      │
      ▼
  State Machine fully caught up
      │
      ▼
  Node joins cluster:
      FOLLOWER (default) or LEADER (if elected)
      │
      ▼
  Ready to serve:
      Leader  → accept writes + reads
      Follower → accept reads only (balance queries)


═══════════════════════════════════════════════════════════════
  FAILURE SCENARIOS
═══════════════════════════════════════════════════════════════

  Scenario 1: Leader crashes
  ─────────────────────────
  T=0s     Leader (Node-1) process dies
  T=1-3s   Follower election timeout triggers
           Node-2 or Node-3 becomes new Leader
  T=3s     New leader accepts writes
           Data loss: 0 (committed entries are on 2/3 nodes)

  Scenario 2: Follower crashes
  ────────────────────────────
  No impact on writes (leader + 1 follower = quorum)
  Follower restarts → snapshot + log replay → caught up

  Scenario 3: Network partition (1 node isolated)
  ───────────────────────────────────────────────
  If leader isolated → new leader elected among 2 connected
  If follower isolated → no impact, rejoins when healed
  Quorum: 2 of 3 required for commits

  Scenario 4: 2 nodes crash simultaneously
  ─────────────────────────────────────────
  Cluster unavailable (no quorum)
  When 1 node recovers → can form quorum with surviving node
  No data loss (committed entries survive on any 2 nodes)
```

---

## 11. CQRS Summary

```
┌──────────────────────────────────────────────────────────────────┐
│                     WRITE SIDE (Command)                          │
│                                                                   │
│  Source of Truth: Raft Log → In-Memory StateMachine → RocksDB    │
│  Accessed by:     Leader node only                                │
│  Consistency:     Strong (Raft quorum commit)                    │
│  Latency target:  P95 ≤ 3ms (posting), ≤ 2ms (balance query)    │
│                                                                   │
│  Operations:                                                      │
│    POST /ledger/postings                 → applyPosting()        │
│    POST /ledger/journals/{id}/reversal   → applyReversal()       │
│    POST /ledger/adjustments/drafts       → applyAdjustment()     │
│    POST /ledger/accounts                 → applyAccountCreate()  │
│    PATCH /ledger/accounts/{id}/freeze    → applyFreeze()         │
│    PATCH /ledger/accounts/{id}/close     → applyCloseAccount()   │
└──────────────────────────────────────────────────────────────────┘
                          │
                          │ BalanceChangeEvent / AccountCreatedEvent
                          │ via Kafka
                          ▼
┌──────────────────────────────────────────────────────────────────┐
│                      READ SIDE (Query)                            │
│                                                                   │
│  Balance (real-time):  In-memory on ANY node     Latency: <2ms   │
│  Journal (historical): MySQL via Projection      Latency: <30ms  │
│  Reconciliation:       MySQL + in-memory         Latency: <2min  │
│  Consistency:          Eventual (~ms for balance, ~1s for MySQL)  │
│                                                                   │
│  Operations:                                                      │
│    GET /ledger/accounts/{id}/balances    → in-memory BalanceStore │
│    GET /ledger/journals                  → MySQL via Projection   │
│    POST /ledger/reconciliation/trigger   → MySQL + in-memory      │
└──────────────────────────────────────────────────────────────────┘
```

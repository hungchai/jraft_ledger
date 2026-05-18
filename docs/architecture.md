# Ledger Platform — Overall Architecture

```
                            ┌──────────────────────────────┐
                            │         API Consumer          │
                            │    (RFQ Engine, Withdrawal,   │
                            │     Order Mgmt, Admin)        │
                            └──────┬───────────┬───────────┘
                                   │           │
                         Writes   │           │  Reads (any node)
                      (leader only)│           │
                                   │           │
        ┌──────────────────────────┼───────────┼──────────────────────────┐
        │                          ▼           ▼                          │
        │   ┌─────────────────────────────────────────────────────────┐   │
        │   │              Raft Cluster (3 nodes)                      │   │
        │   │                                                          │   │
        │   │   ┌──────────────┐  ┌──────────────┐  ┌──────────────┐   │   │
        │   │   │  Ledger-1    │  │  Ledger-2    │  │  Ledger-3    │   │   │
        │   │   │  (Leader)    │  │  (Follower)  │  │  (Follower)  │   │   │
        │   │   │  :8081       │  │  :8082       │  │  :8083       │   │   │
        │   │   │              │  │              │  │              │   │   │
        │   │   │ ┌──────────┐ │  │ ┌──────────┐ │  │ ┌──────────┐ │   │   │
        │   │   │ │StateMach │◄┼──┼─┤StateMach │ │  │ │StateMach │ │   │   │
        │   │   │ │(in-mem)  │ │  │ │(in-mem)  │ │  │ │(in-mem)  │ │   │   │
        │   │   │ └────┬─────┘ │  │ └──────────┘ │  │ └──────────┘ │   │   │
        │   │   │      │       │  │              │  │              │   │   │
        │   │   │ ┌────▼─────┐ │  │              │  │              │   │   │
        │   │   │ │ RocksDB  │ │  │              │  │              │   │   │
        │   │   │ │(persist) │ │  │              │  │              │   │   │
        │   │   │ └──────────┘ │  │              │  │              │   │   │
        │   │   └──────┬───────┘  └──────────────┘  └──────────────┘   │   │
        │   │          │                                                 │   │
        │   │          │ Raft Log Replication (Bolt RPC)                 │   │
        │   │          │                                                 │   │
        │   │   ┌──────▼──────────────────────────────────────────────┐ │   │
        │   │   │                   Kafka Cluster                      │ │   │
        │   │   │                                                      │ │   │
        │   │   │  Topic: ledger.balance.change.v1      (per-line)     │ │   │
        │   │   │  Topic: ledger.posting.completion.v1  (per-request)  │ │   │
        │   │   │                                                      │ │   │
        │   │   │  Partition Key: accountId:balanceType:currency       │ │   │
        │   │   │  64 partitions, LZ4 compression, 7-day retention     │ │   │
        │   │   └──────────┬───────────────────────────────────────────┘ │   │
        │   │              │                                              │   │
        │   │              │  Async consume (at-least-once)               │   │
        │   │              ▼                                              │   │
        │   │   ┌──────────────────────────────────────────────────────┐ │   │
        │   │   │           Projection Service (CQRS Read Side)         │ │   │
        │   │   │                                                       │ │   │
        │   │   │  • Consumes Kafka balance.change.v1                  │ │   │
        │   │   │  • Projects to MySQL: journal, journal_line, balance  │ │   │
        │   │   │  • Idempotent (INSERT ... ON DUPLICATE KEY)           │ │   │
        │   │   │  • Stateless — can scale horizontally                │ │   │
        │   │   └──────────┬───────────────────────────────────────────┘ │   │
        │   │              │                                              │   │
        │   │              ▼                                              │   │
        │   │   ┌──────────────────────────────────────────────────────┐ │   │
        │   │   │               MySQL 8.4 (View Layer)                  │ │   │
        │   │   │                                                       │ │   │
        │   │   │  Tables: journal, journal_line, account,              │ │   │
        │   │   │          balance_type_registry, balance_snapshot      │ │   │
        │   │   │                                                       │ │   │
        │   │   │  Used by: Journal Query, Reconciliation               │ │   │
        │   │   │  NOT used by: Balance Query (reads in-memory)        │ │   │
        │   │   └──────────────────────────────────────────────────────┘ │   │
        │   └────────────────────────────────────────────────────────────┘   │
        └────────────────────────────────────────────────────────────────────┘
```

## Data Flow

```
 POST /ledger/postings
        │
        ▼
  PostingController (leader check)
        │
        ▼
  LedgerStateMachine.applyPosting()
        │
        ├──1. Idempotency check (in-memory)
        ├──2. Account status check (in-memory)
        ├──3. Balance validation (in-memory)
        ├──4. Generate Journal + JournalLines
        ├──5. Update balanceStore (in-memory, accountSeq++)
        ├──6. Publish BalanceChangeEvent → listener
        ├──7. Enqueue event to OutboxStore (RocksDB CF_OUTBOX)
        ├──8. Record idempotency
        ├──9. takeSnapshot() → RocksDB (all CFs)
        └──10. Return CommandResult
                │
                ▼
         KafkaEventPublisher.onEvent(event)
                │
                ▼
         Kafka Topic: ledger.balance.change.v1
                │
                ▼
         ProjectionConsumer.onBalanceChange(message)
                │
                ▼
         MySQL: INSERT journal + journal_line
```

## CQRS Read/Write Split

| Operation | Which Node | Storage | Consistency |
|---|---|---|---|
| Posting / Reversal / Adjustment | **Leader only** | RocksDB | Strong (Raft Quorum) |
| Balance Query | **Any node** | In-memory StateMachine | Eventual (~ms lag) |
| Journal Query | **Any node** | MySQL (via Projection) | Eventual (~1s lag) |
| Reconciliation | **Any node** | MySQL + in-memory | Eventual (T+0) |

## Service Architecture

```
┌─────────────────────────────────────────────────────────┐
│                   Docker Compose Stack                    │
│                                                          │
│  ledger-node-1   :8081,28081    Raft Leader/Follower     │
│  ledger-node-2   :8082,28082    Raft Leader/Follower     │
│  ledger-node-3   :8083,28083    Raft Leader/Follower     │
│  ledger-mysql    :3306          MySQL 8.4 View Layer     │
│  ledger-kafka    :9092          Kafka Broker             │
│  ledger-projection :8089        Projection Consumer      │
│                                                          │
│  Host mounts: ./jraft_ledger/{node1,node2,node3,mysql,kafka} │
│  Network: ledger-net (bridge)                            │
└─────────────────────────────────────────────────────────┘
```

## Module Dependency Graph

```
ledger-core        ← domain, state machine, rocksdb, stores
    ↑
ledger-dao         ← MyBatis mappers (depends on core)
    ↑
ledger-service     ← business services (depends on core + dao)
    ↑
ledger-restful     ← Spring Boot + REST controllers (depends on service)
ledger-feign        ← OpenFeign clients (depends on core)
ledger-projection   ← Kafka consumer → MySQL (depends on core + dao)
```

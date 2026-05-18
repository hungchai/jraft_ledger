# Persistence Flow — Posting API to DB

```
                          ┌─────────────────────────────┐
                          │         Client / Caller       │
                          └─────────────┬───────────────┘
                                        │ POST /ledger/postings
                                        ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         REST Layer (any node)                            │
│                                                                          │
│  ┌──────────────────────────────────┐                                    │
│  │       PostingController          │  1. Deserialize JSON → PostingCmd │
│  │                                  │  2. Check NodeRole.isLeader()     │
│  │   ❌ FOLLOWER → HTTP 503         │     (writes only on leader)       │
│  │   ✅ LEADER  → delegate          │                                    │
│  └──────────────┬───────────────────┘                                    │
│                 │                                                        │
│  ┌──────────────▼───────────────────┐                                    │
│  │        PostingService            │  Thin wrapper → delegates         │
│  └──────────────┬───────────────────┘                                    │
└─────────────────┼────────────────────────────────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                     LedgerStateMachine.applyPosting()                     │
│                    (synchronized — single-threaded)                       │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │ 1. IDEMPOTENCY CHECK                                             │   │
│  │    idempotencyStore.contains(requestId)?                         │   │
│  │    → YES: return cached result (no re-execution)                 │   │
│  │    → NO:  continue                                               │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │ 2. ACCOUNT STATUS CHECK                                          │   │
│  │    accountMetaStore.get(accountId).status == ACTIVE?             │   │
│  │    → FROZEN: reject ACCOUNT_FROZEN                               │   │
│  │    → CLOSED: reject ACCOUNT_CLOSED                               │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │ 3. PER-LEG BALANCE CHECK                                         │   │
│  │    DEBIT total == CREDIT total per leg?                          │   │
│  │    → unbalanced: reject JOURNAL_UNBALANCED                       │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │ 4. BALANCE VALIDATION (in-memory)                                │   │
│  │    for each JournalLine:                                         │   │
│  │      config = balanceTypeConfigStore.get(type)                   │   │
│  │      after = computeAfterBalance(current, entryType, amount)     │   │
│  │      if !allowNegative && after < 0 → INSUFFICIENT_BALANCE      │   │
│  │      if  allowNegative && after > 0 → CREDIT_EXCEEDS_LIMIT      │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │ 5. GENERATE JOURNAL                                              │   │
│  │    journalId = "JNL-NNNN"                                        │   │
│  │    for each line:                                                │   │
│  │      journalLineId = journalId + "-01"                           │   │
│  │      JournalLine{ balanceBefore, balanceAfter, amount, ... }     │   │
│  │    Journal{ journalType=NORMAL, status=CONFIRMED, lines, ... }   │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │ 6. ATOMIC BALANCE UPDATE (in-memory)                             │   │
│  │    for each line:                                                │   │
│  │      nextSeq = current.accountSeq + 1                             │   │
│  │      balanceStore.put(key, BalanceEntry{after, nextSeq, ...})    │   │
│  │    (accountSeq overflow check: if >= 80% Long.MAX_VALUE → alert) │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │ 7. EVENT PUBLISHING (in-memory → outbox)                         │   │
│  │    for each line:                                                │   │
│  │      event = BalanceChangeEvent{accountSeq, prevAccountSeq, ...} │   │
│  │      eventListener.onEvent(event)   ← Kafka / test capture       │   │
│  │      outboxStore.enqueue(event)     ← RocksDB outbox             │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │ 8. IDEMPOTENCY RECORD                                            │   │
│  │    idempotencyStore.put(requestId, COMPLETED)                    │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │ 9. PERSIST (if persistAfterApply=true)                           │   │
│  │    takeSnapshot() → serialize ALL state to RocksDB:              │   │
│  │                                                                   │   │
│  │    ┌─────────────────────────────────────────────────────────┐   │   │
│  │    │                   RocksDB (Source of Truth)              │   │   │
│  │    │                                                          │   │   │
│  │    │  CF_JOURNAL       → journalId → Journal JSON             │   │   │
│  │    │  CF_JOURNAL_LINE  → journalLineId → JournalLine JSON     │   │   │
│  │    │  CF_BALANCE       → key → BalanceEntry JSON (accountSeq) │   │   │
│  │    │  CF_IDEMPOTENCY   → requestId → IdempotencyEntry JSON    │   │   │
│  │    │  CF_ACCOUNT_META  → accountId → Account JSON             │   │   │
│  │    │  CF_BALANCE_TYPE  → typeCode → BalanceTypeConfig JSON   │   │   │
│  │    │  CF_SM_SNAPSHOT   → full state snapshot                  │   │   │
│  │    │  CF_OUTBOX        → eventId → BalanceChangeEvent JSON    │   │   │
│  │    │                                                          │   │   │
│  │    │  📁 /var/lib/ledger/rocksdb (Docker)                    │   │   │
│  │    │  📁 ./jraft_ledger/node1 (host mount)                    │   │   │
│  │    └─────────────────────────────────────────────────────────┘   │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │ 10. RETURN                                                        │   │
│  │     CommandResult{ status=COMPLETED, journalId="JNL-0001" }      │   │
│  └──────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
                  │
                  │  (async, via Learner or sync service)
                  ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                       MySQL View Layer (Read-Only)                       │
│                                                                          │
│   journal (table)               journal_line (table)                     │
│   ┌──────────────────┐          ┌──────────────────────────┐            │
│   │ journal_id (PK)  │◄─────────│ journal_id (FK)          │            │
│   │ journal_type      │          │ journal_line_id (PK)     │            │
│   │ request_id        │          │ leg_id                  │            │
│   │ business_event_ref │         │ account_id              │            │
│   │ value_date        │          │ balance_type            │            │
│   │ status            │          │ currency                │            │
│   │ cross_period      │          │ entry_type              │            │
│   │ created_at        │          │ amount                  │            │
│   └──────────────────┘          │ balance_before          │            │
│                                  │ balance_after           │            │
│   account (table)                │ config_version          │            │
│   ┌──────────────────┐          │ created_at              │            │
│   │ account_id (PK)  │          └──────────────────────────┘            │
│   │ account_type      │                                                  │
│   │ owner_id          │   balance_type_registry (table)                  │
│   │ status            │   ┌──────────────────────────┐                   │
│   └──────────────────┘   │ type_code (PK)            │                   │
│                           │ config_version            │                   │
│                           └──────────────────────────┘                   │
│                                                                          │
│   Synced by: Raft Learner (async) or JournalSyncService                  │
│   Used for:  Journal Query (F-006), Reconciliation (F-007)               │
│   NOT used:  Balance Query (reads from in-memory StateMachine)           │
└─────────────────────────────────────────────────────────────────────────┘


                          ┌─────────────────────┐
                          │   Kafka (Event Bus)  │
                          │                      │
                          │  Topic:              │
                          │  ledger.balance.     │
                          │  change.v1           │
                          │                      │
                          │  64 partitions       │
                          │  LZ4 compression     │
                          │  acks=all            │
                          └─────────────────────┘
                               ▲
                               │ AsyncKafkaPublisher
                               │ (reads from CF_OUTBOX)
                               │
                          ┌────┴────────────┐
                          │  OutboxStore     │
                          │  (RocksDB CF)    │
                          └─────────────────┘


═════════════════════════════════════════════════════════════════════════════
                         RECOVERY FLOW (on restart)
═════════════════════════════════════════════════════════════════════════════

  App Start
      │
      ▼
  RocksDBManager.open(path)
      │
      ▼
  LedgerStateMachine.restoreFromSnapshot()
      │  reads CF_SM_SNAPSHOT → deserializes:
      │    • balanceStore (with accountSeq)
      │    • accountMetaStore
      │    • balanceTypeConfigStore
      │    • journalStore
      │    • idempotencyStore
      │    • raftLogIndex, journalSequence
      │
      ▼
  StateMachine ready — all state restored
      │
      ▼
  New writes persist via takeSnapshot() after each apply

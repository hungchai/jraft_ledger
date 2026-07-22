# Plan: Fix Outbox Pattern to Eliminate Duplicate Kafka Publishes

## Problem

`AsyncOutboxPublisher` is republishing **every** `BalanceChangeEvent` a second time because the outbox is written unconditionally in `LedgerStateMachine.apply()`.

Current flow (posting path):

```
StateMachine.apply()
  ├─ eventListener.onEvent(event)      // hot path → Kafka (async)
  └─ outboxStore.enqueue(event)        // unconditional → RocksDB outbox

AsyncOutboxPublisher (every 10s, batch=100)
  ├─ readPending(100)
  ├─ kafkaPublisher.onEvent(event)     // republish → Kafka (duplicate!)
  └─ outboxStore.markSent(eventId)     // delete from RocksDB
```

Result:
- Every event appears **twice** in Kafka topic `ledger.balance.change.v1`.
- Projection consumer sees duplicates; handles them via `ConflationQueue` + `projection_event_log.uk_event_seq`, but wastes CPU/DB writes.
- Outbox backlog of ~404k events across 3 nodes will take ~11 hours to drain at 10/s per node.

## Root Cause

`KafkaEventPublisher` uses **async** `producer.send(record, callback)`. At the time `outboxStore.enqueue()` is called, the callback has not fired, so the code cannot know whether the hot-path publish succeeded or failed. Rather than handle this properly, the code unconditionally enqueues and lets `AsyncOutboxPublisher` drain everything — turning the outbox into a **shadow queue** rather than a **recovery buffer**.

## Solution: Transactional Outbox with Callback-Driven Deletion

### Design

1. **StateMachine** writes the event to RocksDB outbox **inside the same `WriteBatch`** as the journal + balance mutation. This is already happening via `outboxStore.enqueue()` → `outboxStore.flush()`.
2. **StateMachine** calls `kafkaPublisher.onEvent(event)` with a callback that will delete the outbox entry on Kafka ack.
3. **`KafkaEventPublisher`** gains a reference to `OutboxStore`. Its send-callback calls `outboxStore.markSent(eventId)` on **success**.
4. **`AsyncOutboxPublisher`** scans outbox for **residual** entries (those whose callback never fired due to crash or Kafka failure) and republishes them. It **does not** call `markSent` itself; it relies on the same `KafkaEventPublisher` callback.

New flow:

```
StateMachine.apply() — inside RocksDB WriteBatch
  ├─ journal + balance + outboxStore.enqueue(event)   // atomic
  └─ kafkaPublisher.onEvent(event, outboxStore)       // async, callback deletes on ack

KafkaEventPublisher.send callback
  ├─ on success → outboxStore.markSent(eventId)
  └─ on failure → leave in outbox ( AsyncOutboxPublisher will retry )

AsyncOutboxPublisher
  ├─ readPending(batchSize)
  ├─ kafkaPublisher.onEvent(event, outboxStore)       // same callback path
  └─ (no direct markSent — callback handles it)
```

### Guarantees

| Scenario | Outcome |
|----------|---------|
| Happy path (hot path Kafka acks) | Event published once; callback deletes outbox; zero duplicate |
| Crash after WriteBatch commit, before callback | Outbox entry remains; AsyncOutboxPublisher rescans after restart and republishes |
| Kafka transient failure (callback gets error) | Outbox entry remains; AsyncOutboxPublisher retries |
| AsyncOutboxPublisher republishes | Same callback path; success → markSent; safe to retry |

## Files to Change

| File | Change |
|------|--------|
| `ledger-core/.../event/KafkaEventPublisher.java` | Add `OutboxStore` field + constructor arg; in send-callback call `outboxStore.markSent(eventId)` on success |
| `ledger-core/.../event/AsyncOutboxPublisher.java` | Remove `outboxStore.markSent()` after publish; remove `outboxStore` dependency if no longer needed directly |
| `ledger-restful/.../rest/config/LedgerConfig.java` | Wire `OutboxStore` into `KafkaEventPublisher` bean |
| `ledger-core/.../statemachine/LedgerStateMachine.java` | Keep unconditional `outboxStore.enqueue(event)` (it is the transactional write); no change required unless we want to remove the hot-path direct call and let outbox be the sole publisher (decision below) |

### Decision: Keep or Remove Hot-Path Direct Publish?

**Option A — Keep hot path + callback deletion (recommended, minimal change)**
- `StateMachine` still calls `eventListener.onEvent(event)` directly.
- Callback deletes outbox on success.
- `AsyncOutboxPublisher` only handles residual/crash cases.
- **Pros**: Low latency happy path; outbox publisher stays as recovery-only background task.
- **Cons**: Slightly more complex callback wiring.

**Option B — Outbox-only publish (simpler, higher latency)**
- `StateMachine` only writes to outbox; never calls `eventListener.onEvent(event)`.
- `AsyncOutboxPublisher` is the sole publisher.
- **Pros**: Simple, no callback wiring, guaranteed ordering.
- **Cons**: Adds 0–10s latency to every BalanceChangeEvent (violates F-011 real-time expectation).

**Recommendation: Option A.**

## Test Impact

- `KafkaIntegrationTest` — may need to mock `OutboxStore` in `KafkaEventPublisher`.
- `ProjectionIntegrationTest` — currently produces directly to Kafka; should be unaffected.
- New unit test: verify `KafkaEventPublisher` callback calls `markSent` on success.
- New unit test: verify `AsyncOutboxPublisher` does **not** call `markSent` directly.
- New integration test: simulate crash → restart → assert outbox residual is republished.

## Compliance Checklist

Per `CLAUDE.md` rules:

- [ ] `LEDGER-PLATFORM-FULL-REQUIREMENTS.md` — update F-011 (BalanceChangeEvent / Kafka Outbox) to reflect transactional outbox semantics
- [ ] `TDD-TEST-CASES.md` — add TC-F011-xx for callback-driven deletion, crash recovery, no-duplicate scenarios
- [ ] `Postman collection` — no REST change, not required
- [ ] `smoke-test.sh` — no API change, not required
- [ ] `init.sql` — no schema change (projection_event_log already has UK on `event_seq`)
- [ ] Prometheus metrics — outbox gauges already exist (`ledger.outbox.pending`, etc.), no new metrics required
- [ ] Compile & test pass (`mvn clean compile`, `mvn test`)

## Acceptance Criteria

1. After fix, `ledger_outbox_pending` on a idle node should drop to **0** within minutes of startup (not hours).
2. New postings during steady state should show `ledger_outbox_pending` briefly spike then return to 0 within one `AsyncOutboxPublisher` poll cycle.
3. Kafka topic `ledger.balance.change.v1` should receive **exactly one** message per posting (verified by consumer group offset growth == posting count).
4. Crash + restart test: stop node mid-publish, restart, assert outbox residual events are republished and projection_event_log has no duplicate `event_id`.
5. All existing tests pass.

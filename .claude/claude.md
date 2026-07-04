# CLAUDE.md

Guidelines cut common LLM coding mistakes. Merge with project instructions.

**Tradeoff:** bias caution over speed. Trivial tasks — use judgment.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implement:
- State assumptions. Uncertain → ask.
- Multiple interpretations → present all; don't pick silent.
- Simpler approach → say so. Push back when warranted.
- Unclear → stop, name confusion, ask.

## 2. Simplicity First

**Minimum code solves problem. Nothing speculative.**

- No features beyond ask.
- No single-use abstractions.
- No unrequested flexibility/config.
- No error handling for impossible cases.
- 200 lines when 50 enough → rewrite.

Ask: senior engineer say overcomplicated? Yes → simplify.

## 3. Surgical Changes

**Touch only must. Clean only own mess.**

Edit existing code:
- Don't improve adjacent code/comments/format.
- Don't refactor unbroken code.
- Match existing style.
- Unrelated dead code → mention, don't delete.

Your changes create orphans:
- Remove imports/vars/functions YOUR changes orphaned.
- Don't remove pre-existing dead code unless asked.

Test: every changed line traces to user request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks:
- "Add validation" → tests for invalid inputs, then pass
- "Fix bug" → test reproduces, then pass
- "Refactor X" → tests pass before + after

Multi-step → brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong criteria → loop alone. Weak ("make it work") → need clarification.

---

**Working if:** fewer unnecessary diffs, fewer overcomplication rewrites, questions before implementation not after mistakes.

---

# Rules for Next-Gen Internal Ledger Platform

1. Before Every Code Change
2. Code-Change Rules
3. After Every Code Change — Mandatory Updates
4. Monitoring & Observability Requirements
5. Audit & Traceability
6. PR / Commit Gate Checklist
7. Quick Feature-to-File Reference

claude.md — AI agent rules for Next-Gen Internal Ledger Platform. Follow before, during, after any code change.

## 1. Before Every Code Change

### 1.1 Requirement Review Checklist

Before code, read + cross-check:

`LEDGER-PLATFORM-FULL-REQUIREMENTS.md` — Feature (F-001–F-011) + ADR(s) in scope

`TDD-TEST-CASES.md` — TC-Fxxx-xx IDs for affected feature(s)

Critical domains (extra scrutiny):

- Balance Type Registry (F-001)
- Posting / Multi-Account Atomic Commit (F-002, ADR-001)
- Reversal (F-004)
- Manual Adjustment / Maker-Checker (F-003)
- State Machine (F-008)
- Reconciliation L1/L2/L3 (F-007)
- Accounting Period / EOD (F-009)
- Account Management (F-010)
- BalanceChangeEvent / Kafka Outbox (F-011)

## 2. Code-Change Rules

### 2.1 Immutability & Append-Only Journal

Journal + JournalLines append-only. Never UPDATE/DELETE journal records.

Reversals → new `journalType=REVERSAL`; original `REVERSED` only via State Machine apply.

`crossPeriod=true` when reversal `valueDate` crosses period boundary.

### 2.2 Idempotency

All writes (Posting, Reversal, Adjustment, Account create) check `idempotencyStore` first.

Duplicate `requestId` → return cached result; never re-execute.

`requestId`: UUID v7.

### 2.3 State Machine Integrity

Balance mutations: Raft Leader → Account Queue → State Machine apply only.

Direct `balanceStore` / RocksDB writes outside `StateMachine.apply()` forbidden.

`accountSeq` atomic increment per `(accountId, balanceType, currency)` every apply.

Every `JournalLine`: `balanceBefore` + `balanceAfter`.

### 2.4 Balance Floor & Ceiling Enforcement

`allowNegative=false` + `afterBalance < 0` → `INSUFFICIENT_BALANCE`

`allowNegative=true` + CREDIT above 0 past limit → `CREDIT_EXCEEDS_LIMIT`

Respect `zeroFloorEnforce` from `BalanceTypeConfig`.

### 2.5 Account Queue Ordering

Same `accountId` → serialize via `LinkedBlockingQueue`.

Multi-account (RFQ CLIENT ↔ COMPANY): acquire queues lexicographic `accountId` order — no deadlock.

Queue depth > `MAX_QUEUE_SIZE` → reject `QUEUE_FULL`.

### 2.6 RocksDB WriteBatch Atomicity

Journal + JournalLine + Balance in one `WriteBatch` per command.

No partial state; crash recovery = WAL atomicity.

### 2.7 Maker-Checker Enforcement

Adjustment: `checkerId ≠ makerId` or `MakerCheckerSamePersonException`.

Check `Draft.expiresAt` before approve; stale → `DraftExpiredException`.

### 2.8 Accounting Period Gates

Postings (non-reversal) when period `CLOSING`/`CLOSED` → `PERIOD_CLOSED`.

Cross-period Reversals: `crossPeriod=true` + approval only.

### 2.9 SQL Schema Documentation Standard

Every `init.sql` column: inline `COMMENT` (purpose, domain, unit).

```sql
-- ✅ Correct
amount DECIMAL(24,8) NOT NULL COMMENT 'Transaction amount in the currency unit (e.g., USD cents not required — use decimal unit)',
status VARCHAR(16) NOT NULL COMMENT 'Account lifecycle status: ACTIVE | FROZEN | CLOSED',
account_seq BIGINT NOT NULL DEFAULT 0 COMMENT 'Monotonic sequence number incremented per (account_id, balance_type, currency) on every state machine apply',

-- ❌ Wrong
amount DECIMAL(24,8) NOT NULL,
status VARCHAR(16) NOT NULL,
```

Rules:
- Every column `COMMENT` — no bare columns.
- New table → all columns described.
- New column → `ALTER TABLE ... ADD COLUMN ... COMMENT '...'`
- Enum columns: valid values in comment.
- Numeric: unit/semantics in comment.
- Timestamps: precision + timezone in comment.
- FK columns: target in comment.
- Boolean: true/false meaning in comment.

### 2.10 Performance Non-Negotiables

| Operation | Target | Violation Action |
|---|---|---|
| Posting P95 | ≤ 3 ms | Reject PR, profile hotspot |
| Balance Query (live) | ≤ 2 ms | Reject PR, check in-memory path |
| Balance Query (as-of) | ≤ 30 ms | Reject PR, check Learner/MySQL |
| Journal Query | ≤ 30 ms | Reject PR |
| Reconciliation Report | ≤ 2 min | Reject PR |

### 2.11 Spring Boot Configuration (Mandatory)

**Do not** read config in application code via `Environment.getProperty(...)`, `System.getenv(...)`, or scattered `@Value` on `@Configuration` beans.

| Do | Don't |
|---|---|
| Define keys in `application.yml` / `application-{profile}.yml` | Hard-code defaults in Java |
| Bind with `@ConfigurationProperties` classes under `...config.properties` | `env.getProperty("ledger.*")` in `@Bean` methods |
| Inject `LedgerProperties` / `OutboxProperties` into `@Configuration` | `System.getenv("NODE_ID")` in controllers or config |
| Keep legacy env names only as `${ENV:default}` in yml | Duplicate property paths in Java strings |
| Use `SpringConfigService` only for `ConfigService` bridge (ledger-core) | New code depending on `ConfigService` when a properties bean exists |

**Pattern:**

```yaml
# application.yml
ledger:
  kafka:
    required: ${LEDGER_KAFKA_REQUIRED:false}
```

```java
@ConfigurationProperties(prefix = "ledger")
public class LedgerProperties { ... }

@Bean
KafkaEventPublisher kafka(Kafka kafka = ledgerProps.getKafka()) { ... }
```

Enable via `@EnableConfigurationProperties` in `LedgerPropertiesConfiguration`.

**Env vars:** allowed in yml as `${VAR:default}` — Spring resolves them into the properties bean at startup. Java code reads the bean, not the OS env.

## 3. After Every Code Change — Mandatory Updates

Complete after any code change.

### 3.1 Update LEDGER-PLATFORM-FULL-REQUIREMENTS.md

| Trigger | Required Update |
|---|---|
| New API field/endpoint | Feature spec request/response schema |
| New error code | Error enum/table in Feature section |
| New Balance Type, alert, config | F-001 schema |
| ADR revised | ADR section + rationale + date |
| NFR target changed | NFR table |
| New feature | New F-0xx section + acceptance criteria |

Bump doc version (patch) + changelog.

### 3.2 Update TDD-TEST-CASES.md

- Affected TC IDs → update Given/When/Then if behaviour changes
- Add TCs: happy path, rejection, idempotency, edge cases
- IDs: `TC-{MODULE}-{NN}` (e.g. `TC-F002-11`)
- Update phase mapping table if new module/phase
- Bump version + changelog

| Change Type | Minimum New TCs |
|---|---|
| New happy path | 1 |
| New rejection / error path | 1 per error code |
| Idempotency | 1 |
| Concurrency / hotspot | 1 (shared-account paths) |
| Snapshot / replay | 1 (State Machine touched) |

### 3.3 Update Postman Collection

Update when: new/changed endpoint, schema change, new errors, auth change.

### 3.4 Update Smoke Test Script

Update `scripts/smoke-test.sh` when: schema change, new required fields, API structure change, new validation.

Per endpoint in Postman:

```
POST   /ledger/postings                    → happy path + idempotency + insufficient balance
POST   /ledger/journals/{id}/reversal      → confirmed + already-reversed + cross-period
POST   /ledger/adjustments/drafts          → create draft + approve + reject + maker=checker error
GET    /ledger/accounts/{id}/balances      → live + as-of + batch
GET    /ledger/journals                    → by account + by businessEventRef + by requestId
POST   /ledger/reconciliation/trigger      → L1 + L2 + L3
GET    /ledger/reconciliation/reports      → report by date
PATCH  /ledger/reconciliation/cases/{id}   → resolve case
POST   /ledger/reconciliation/external-files → upload SWIFT/CSV
POST   /ledger/accounts                    → create + duplicate + freeze + close
```

Conventions:
- Folder = one Feature (F-001, F-002, …)
- Env: `{{baseUrl}}`, `{{authToken}}`, `{{requestId}}`, `{{accountId}}`
- Pre-request: fresh UUID v7 `requestId` on writes
- Tests: HTTP status, body status, key fields
- Negative Cases sub-folder per Feature
- Tag TC-Fxxx-xx in request description

### 3.5 Verify Compilation and Tests

**Step 1:** `mvn clean compile` — zero errors.

**Step 2:** `mvn test` — all pass.

**Step 3:** fix failures before done.

Common failures:
- Record ctor new fields → fix `new RecordName(...)` in tests
- Signature change → fix test callers
- New enum → update assertions
- New API fields → Postman + `smoke-test.sh`
- New validation → align test expectations

Never `-Dmaven.test.skip=true` unless user says.
Never end with failing tests unless user says skip.

## 4. Monitoring & Observability Requirements

Every new path needs:

### 4.1 Structured Logging

```json
{
  "traceId": "...",
  "spanId": "...",
  "requestId": "...",
  "accountId": "...",
  "journalId": "...",
  "operationType": "POSTING | REVERSAL | ADJUSTMENT | QUERY | RECONCILIATION",
  "durationMs": 0,
  "outcome": "COMPLETED | REJECTED | ERROR",
  "errorCode": "...",
  "operator": "..."
}
```

- `INFO` completed
- `WARN` rejected (business rules)
- `ERROR` system + stack trace
- No balance amounts or owner PII at `DEBUG` in prod

### 4.2 Metrics (Micrometer / Prometheus)

| Metric | Type | Labels |
|---|---|---|
| `ledger.posting.duration` | Histogram | outcome, balanceType |
| `ledger.posting.rejected.count` | Counter | errorCode |
| `ledger.balance.query.duration` | Histogram | queryType (live/asof/eod) |
| `ledger.state_machine.queue.depth` | Gauge | accountId (sampled) |
| `ledger.reconciliation.cases.open` | Gauge | reconType (L1/L2/L3) |
| `ledger.raft.leader.election.count` | Counter | — |
| `ledger.kafka.publish.lag` | Gauge | topic |

### 4.3 Alerting Rules

PagerDuty critical:
- Posting P95 > 3 ms sustained 2 min
- `TRADEAHEADBALANCE` > `overdrawnAlertThreshold` (-500,000)
- Unexpected Raft leader election
- Recon OPEN cases > 0 after T+1 09:00
- Kafka lag > 1,000 on `balance-change-events`
- `JOURNAL_UNBALANCED` in L1 recon

## 5. Audit & Traceability

Every write:
- `createdBy`, `createdAt`, `lastModifiedBy`, `lastModifiedAt`, `changeReason` on mutable registry
- `operatorId`, `approvalRef` on Reversal + Adjustment
- `configVersion` snapshot on every JournalLine
- Maker-Checker log: draft create/approve/reject, operator IDs, timestamps
- Recon case: `OPEN → IN_PROGRESS → RESOLVED / WAIVED` + `resolvedAt`, `resolutionAction`, `resolutionJournalId`

## 6. PR / Commit Gate Checklist

```
[ ] LEDGER-PLATFORM-FULL-REQUIREMENTS.md reviewed and updated if needed
[ ] TDD-TEST-CASES.md updated with new/modified test cases
[ ] Postman collection updated for all affected endpoints
[ ] All new test cases follow TC-Fxxx-xx naming convention
[ ] No direct balance mutation outside StateMachine.apply()
[ ] idempotencyStore checked on all write paths
[ ] accountSeq incremented correctly per (accountId, balanceType, currency)
[ ] RocksDB WriteBatch used for atomic Journal + Balance writes
[ ] Structured logging with required fields added to new code paths
[ ] Prometheus metrics instrumented for new endpoints
[ ] No PII or balance data logged at DEBUG in production profile
[ ] Performance targets verified (P95 Posting ≤3ms, Balance Query ≤2ms)
[ ] LEDGER-PLATFORM-FULL-REQUIREMENTS.md version bumped if doc updated
[ ] TDD-TEST-CASES.md version bumped if doc updated
[ ] Postman collection updated for all affected endpoints
[ ] smoke-test.sh updated if API structure changed
[ ] Code compiles successfully (`mvn clean compile` passes)
[ ] All tests pass (`mvn test` passes) — if test fails, fix before concluding
[ ] All table/column changes in init.sql include COMMENT descriptions per 2.9
[ ] No test files left with compilation errors from constructor/field changes
```

## 7. Quick Feature-to-File Reference

| Feature | Requirement Section | Test Case Prefix | Postman Folder |
|---|---|---|---|
| Balance Type Registry | F-001 | TC-F001- | F-001 Balance Registry |
| Posting API v2 | F-002, ADR-001 | TC-F002- | F-002 Posting |
| Manual Adjustment | F-003 | TC-F003- | F-003 Adjustment |
| Reversal | F-004 | TC-F004- | F-004 Reversal |
| Balance Query v2 | F-005 | TC-F005- | F-005 Balance Query |
| Journal Query | F-006 | TC-F006- | F-006 Journal Query |
| Reconciliation L1/L2/L3 | F-007 | TC-F007- | F-007 Reconciliation |
| State Machine | F-008, ADR-001 | TC-F008- | — (internal) |
| Accounting Period / EOD | F-009 | TC-F009- | F-009 EOD |
| Account Management | F-010 | TC-F010- | F-010 Accounts |
| BalanceChangeEvent / Outbox | F-011 | TC-F011-, TC-KAFKA- | — (internal) |
| RocksDB / WAL | ADR-001 | TC-ROCKS- | — (internal) |
| Account Queue | ADR-001 | TC-QUEUE- | — (internal) |
| Raft Cluster | ADR-001 | TC-RAFT- | — (internal) |
| NFR | NFR section | TC-NFR- | — (load test) |

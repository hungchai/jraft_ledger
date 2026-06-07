# CLAUDE.md

Guidelines reduce common LLM coding mistakes. Merge with project instructions as needed.

**Tradeoff:** Guidelines bias caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them — don't pick silently.
- If simpler approach exists, say so. Push back when warranted.
- If something unclear, stop. Name what confusing. Ask.

## 2. Simplicity First

**Minimum code solves problem. Nothing speculative.**

- No features beyond what asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" not requested.
- No error handling for impossible scenarios.
- If you write 200 lines and could be 50, rewrite it.

Ask: "Would senior engineer say this overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what must. Clean only own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things not broken.
- Match existing style, even if you'd do differently.
- If you notice unrelated dead code, mention — don't delete.

When your changes create orphans:
- Remove imports/variables/functions YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line trace directly to user request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make pass"
- "Fix bug" → "Write test reproduces it, then make pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria → loop independently. Weak criteria ("make it work") → constant clarification.

---

**These guidelines working if:** fewer unnecessary changes in diffs, fewer rewrites from overcomplication, clarifying questions come before implementation not after mistakes.

---

# Rules for Next-Gen Internal Ledger Platform

1. Before Every Code Change
2. Code-Change Rules
3. After Every Code Change — Mandatory Updates
4. Monitoring & Observability Requirements
5. Audit & Traceability
6. PR / Commit Gate Checklist
7. Quick Feature-to-File Reference

claude.md — AI Coding Agent Rules for Next-Gen Internal Ledger Platform
This file governs every code-change session.
AI agent must follow all rules before, during, after any code modification.

## 1. Before Every Code Change

### 1.1 Requirement Review Checklist

Before writing or modifying code, agent must read and cross-check:

`LEDGER-PLATFORM-FULL-REQUIREMENTS.md` — confirm which Feature (F-001–F-011) and ADR(s) in scope

`TDD-TEST-CASES.md` — identify all test case IDs (TC-Fxxx-xx) mapping to affected feature(s)

Confirm whether change touches any critical domains (extra scrutiny required):

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

Journal entries and JournalLines append-only. Never UPDATE or DELETE journal records.

All reversals must create new `journalType=REVERSAL` journal; original journal's status set to `REVERSED` only via State Machine apply path.

`crossPeriod` flag must be set when reversal `valueDate` crosses accounting period boundary.

### 2.2 Idempotency

Every write path (Posting, Reversal, Adjustment, Account creation) must check `idempotencyStore` before execution.

Return cached result immediately on duplicate `requestId`; never re-execute.

`requestId` format: UUID v7.

### 2.3 State Machine Integrity

All balance mutations go through Raft Leader → Account Queue → State Machine apply path.

Direct writes to `balanceStore` or RocksDB outside `StateMachine.apply()` forbidden.

`accountSeq` must increment atomically per `(accountId, balanceType, currency)` key on every apply (Posting, Reversal, Adjustment).

`balanceBefore` and `balanceAfter` must record on every `JournalLine`.

### 2.4 Balance Floor & Ceiling Enforcement

If `allowNegative=false`: reject if `afterBalance < 0` → error `INSUFFICIENT_BALANCE`

If `allowNegative=true` and CREDIT would push balance above 0 beyond limit: reject → error `CREDIT_EXCEEDS_LIMIT`

`zeroFloorEnforce` flag must respect `BalanceTypeConfig` definition.

### 2.5 Account Queue Ordering

All operations targeting same `accountId` must serialize through its `LinkedBlockingQueue`.

Multi-account operations (e.g., RFQ CLIENT ↔ COMPANY) must acquire queues in deterministic order (sort by `accountId` lexicographically) to prevent deadlock.

Back-pressure: reject enqueue if queue depth exceeds `MAX_QUEUE_SIZE`; return `QUEUE_FULL` error.

### 2.6 RocksDB WriteBatch Atomicity

Journal, JournalLine, and Balance updates must commit in single `WriteBatch` per command.

Never write partial state; crash-recovery relies on WAL atomicity.

### 2.7 Maker-Checker Enforcement

Adjustment drafts require `checkerId ≠ makerId`; throw `MakerCheckerSamePersonException` otherwise.

`Draft.expiresAt` must check before approval; throw `DraftExpiredException` for stale drafts.

### 2.8 Accounting Period Gates

Block new Postings (non-reversal) when period status `CLOSING` or `CLOSED` → `PERIOD_CLOSED`.

Allow cross-period Reversals only with explicit `crossPeriod=true` in request and approval.

### 2.9 SQL Schema Documentation Standard

Every table column in `init.sql` must carry inline `COMMENT` describing column purpose, domain, and unit where applicable.

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
- Every column gets `COMMENT` — no bare columns.
- New table → all columns described immediately.
- New column added to existing table → `ALTER TABLE ... ADD COLUMN ... COMMENT '...'` must include description.
- Enum-like columns list valid values in comment (e.g., `'Account lifecycle status: ACTIVE | FROZEN | CLOSED'`).
- Numeric columns specify unit/semantics (e.g., `'Transaction amount in the currency unit'`, `'Balance after posting in account currency'`).
- Timestamp columns note precision and timezone (e.g., `'Record creation timestamp (UTC, microsecond precision)'`).
- Foreign key columns reference target (e.g., `'Foreign key to journal.journal_id'`).
- Boolean columns describe what true/false mean.

### 2.10 Performance Non-Negotiables

| Operation | Target | Violation Action |
|---|---|---|
| Posting P95 | ≤ 3 ms | Reject PR, profile hotspot |
| Balance Query (live) | ≤ 2 ms | Reject PR, check in-memory path |
| Balance Query (as-of) | ≤ 30 ms | Reject PR, check Learner/MySQL |
| Journal Query | ≤ 30 ms | Reject PR |
| Reconciliation Report | ≤ 2 min | Reject PR |

## 3. After Every Code Change — Mandatory Updates

Agent must complete all following steps after modifying code.

### 3.1 Update LEDGER-PLATFORM-FULL-REQUIREMENTS.md

Check if change affects:

| Trigger | Required Update |
|---|---|
| New API field or endpoint added | Add to relevant Feature spec (request/response schema table) |
| New error code introduced | Add to error code enum/table in relevant Feature section |
| New Balance Type, alert rule, or config field | Update F-001 Balance Type Registry schema |
| ADR decision revised | Update relevant ADR section with rationale and date |
| NFR target changed | Update NFR table |
| New feature or sub-feature | Add new Feature spec section (F-0xx) with acceptance criteria |

Requirement doc version must increment (patch bump, e.g. v0.2 → v0.3) and change log row updated.

### 3.2 Update TDD-TEST-CASES.md

For every code change:

- Identify all existing test case IDs affected — update Given / When / Then if behaviour changes

Add new test cases for:
- New happy-path behaviour
- New error / rejection path
- Any new idempotency scenario
- Any new edge case identified during implementation

Assign IDs following pattern `TC-{MODULE}-{NN}` (e.g. `TC-F002-11`).

Update TDD Phase mapping table at bottom of file if new module or phase added.

Bump TDD-TEST-CASES.md version header and change log.

Minimum test case coverage per change:

| Change Type | Minimum New TCs |
|---|---|
| New happy path | 1 |
| New rejection / error path | 1 per new error code |
| Idempotency scenario | 1 |
| Concurrency / hotspot scenario | 1 (for any shared-account path) |
| Snapshot / replay scenario | 1 (if State Machine touched) |

### 3.3 Update Postman Collection

Postman collection file (or `postman/` directory) must update whenever:

- New REST endpoint added or existing modified
- Request/response schema changes (new fields, renamed fields, removed fields)
- New error codes returned
- Authentication headers or parameters change

### 3.4 Update Smoke Test Script

`scripts/smoke-test.sh` must update whenever:

- Request/response schema changes (new fields, renamed fields, removed fields)
- New required fields added to existing endpoints
- API structure changes (e.g., amount/currency moved from line to leg level)
- New validation rules affect smoke test scenarios

For each affected endpoint, ensure Postman collection contains:

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

Postman collection conventions:
- Each folder maps to one Feature (F-001, F-002, …)
- Use environment variables: `{{baseUrl}}`, `{{authToken}}`, `{{requestId}}`, `{{accountId}}`
- Pre-request scripts must generate fresh UUID v7 `requestId` for write operations
- Test scripts must assert: HTTP status code, status field in response body, key response fields
- Add Negative Cases sub-folder per Feature for all rejection/error scenarios
- Tag each request with corresponding TC-Fxxx-xx ID in request description

### 3.5 Verify Compilation and Tests

After any code change, agent must:

**Step 1: Compile**
Run `mvn clean compile` — confirm zero compilation errors.

**Step 2: Run ALL unit tests**
Run `mvn test` — confirm ALL tests pass, zero failures, zero errors.

**Step 3: Fix failures before concluding**
If compilation or any test fails, fix root cause before concluding session. Do not leave failing tests behind.

Common failure patterns:
- Record constructors with new required fields → update all `new RecordName(...)` in tests
- Method signature changes → update all callers in tests
- New enum values → update test assertions if applicable
- New required fields in API request/response → update Postman collection and `smoke-test.sh`
- New validation rules → ensure existing test expectations align with new behavior

Never skip test compilation with `-Dmaven.test.skip=true` unless explicitly instructed.
Never conclude session with failing tests unless user explicitly asks to skip.

## 4. Monitoring & Observability Requirements

Every new code path must include:

### 4.1 Structured Logging

All log entries must include these fields:

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

- Log at `INFO` for completed operations
- Log at `WARN` for rejected operations (business rule violations)
- Log at `ERROR` for system errors with full stack trace
- Never log balance amounts or account owner PII at `DEBUG` level in production profile

### 4.2 Metrics (Micrometer / Prometheus)

Instrument every new endpoint/service method with:

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

Critical alerts (PagerDuty) must be in place for:

- Posting P95 > 3 ms sustained for 2 minutes
- Any `TRADEAHEADBALANCE` crossing `overdrawnAlertThreshold` (-500,000)
- Raft leader election triggered (unexpected)
- Reconciliation OPEN cases > 0 after T+1 09:00
- Kafka consumer lag > 1,000 messages for `balance-change-events` topic
- Any `JOURNAL_UNBALANCED` case detected in L1 reconciliation

## 5. Audit & Traceability

Every write operation must produce auditable trail:

- `createdBy`, `createdAt`, `lastModifiedBy`, `lastModifiedAt`, `changeReason` on all mutable registry records
- `operatorId` and `approvalRef` on all Reversal and Adjustment requests
- `configVersion` snapshot on every JournalLine (records Balance Type config active at posting time)
- Maker-Checker audit log: draft creation, approval/rejection, operator IDs, timestamps
- Reconciliation case lifecycle: `OPEN → IN_PROGRESS → RESOLVED / WAIVED` with `resolvedAt`, `resolutionAction`, `resolutionJournalId`

## 6. PR / Commit Gate Checklist

Agent must confirm all items before concluding session:

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
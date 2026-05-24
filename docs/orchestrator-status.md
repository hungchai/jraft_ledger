## [2026-05-24 12:11] Hotfix — ledger-orchestrator (gaps #1-#6, #11)
Status: ✅ PASS
Summary: Fixed critical 🔴 gaps #1 (debit=credit validation with seed exemption), #2 (atomic RocksDB WriteBatch persistence via persistApply), #4 (removed global synchronized; added per-account ReentrantLock as interim until AccountQueueManager wired), #5 (durable idempotency writes to CF_IDEMPOTENCY), #6 (AdjustmentCommand + applyAdjustment producing MANUAL_ADJUSTMENT journals), #11 (position column in MySQL schema, mappers, ProjectionConsumer). Updated RocksDBIntegrationTest and LedgerStateMachineTest data to use balanced postings. mvn clean compile and mvn test all modules GREEN.
Findings:
- Gap #3 (AccountQueueManager wiring) remains open; per-account locking in LedgerStateMachine is interim safeguard pending full queue integration.
- LedgerStateMachineTest.ConcurrencyTest passes with per-account locking.
Next: ledger-reviewer re-review diff (hotfix pipeline step 3), then smoke-tester.

---

## [2026-05-24 10:30] Step QA — qa-engineer (full traceability audit)
Status: ❌ FAIL
Summary: Full requirement-to-code gap analysis complete. 18 critical 🔴 gaps found across F-001~F-013 + ADR-001 + NFR. Code compiles but several core financial invariants are unimplemented or violated.
Findings:

### 🔴 Critical — Financial Correctness / Safety

1. **F-002 V-05 | LedgerStateMachine.applyPosting** — Missing debit=credit journal balance validation. Code checks per-line allowNegative but never verifies total DEBIT equals total CREDIT per leg or globally. Unbalanced journals can be posted.
   → `ledger-core/.../LedgerStateMachine.java:192` applyPosting

2. **F-008 §5.3 | LedgerStateMachine apply path** — No RocksDB WriteBatch atomicity during apply. All CFs (CF_JOURNAL, CF_JOURNAL_LINE, CF_BALANCE, CF_IDEMPOTENCY) are only written during optional `takeSnapshot()`, not on every apply. Crash between applies loses idempotency and journal data.
   → `ledger-core/.../LedgerStateMachine.java:192-403`

3. **F-013 / ADR-001 | AccountQueueManager bypassed** — Controllers call `raftNodeManager.submit()` directly. `AccountQueueManager` is instantiated but never wired into the request path. Per-account serialization, backpressure, and deadlock prevention are not enforced.
   → `ledger-restful/.../PostingController.java:72`, `ReversalController.java:49`

4. **F-008 / F-013 | applyPosting globally synchronized** — `public synchronized CommandResult applyPosting(...)` serializes ALL postings across all accounts. This masks the missing per-account queue but creates a global bottleneck and violates ADR-001 intent.
   → `ledger-core/.../LedgerStateMachine.java:192`

5. **F-002 / F-004 / F-003 | Idempotency not durable** — `IdempotencyStore` is in-memory `ConcurrentHashMap` only. Not persisted to RocksDB CF_IDEMPOTENCY in the apply hot path. Restart without recent snapshot causes duplicate execution.
   → `ledger-core/.../store/IdempotencyStore.java:9`

6. **F-003 | Adjustment creates NORMAL journal** — `AdjustmentService.approveDraft()` calls `stateMachine.applyPosting(cmd)` which always sets `journalType=NORMAL`. No `applyAdjustment()` method exists. Manual adjustments are indistinguishable from business postings.
   → `ledger-service/.../AdjustmentService.java:69`, `LedgerStateMachine.java:332`

7. **F-004 | Journal missing reversalJournalId** — `Journal` record has no `reversalJournalId` field. After reversal, original journal status becomes REVERSED but linkage to reversal journal is lost. Breaks audit chain and F-006 chain query.
   → `ledger-core/.../domain/model/Journal.java:8`

8. **F-004 / F-009 | crossPeriod logic incorrect** — `applyReversal()` computes crossPeriod by comparing year+month of `cmd.valueDate` vs `originalJournal.valueDate`. Requirement: crossPeriod=true when original journal's accounting period is CLOSED, not merely different month.
   → `ledger-core/.../LedgerStateMachine.java:596`

9. **F-009 / F-002 / F-004 | Period gate missing** — `applyPosting` and `applyReversal` do not check `AccountingPeriod` status. Postings during CLOSING/CLOSED periods are accepted. `PERIOD_CLOSED` error never returned.
   → `ledger-core/.../LedgerStateMachine.java:192`, `applyReversal:493`

10. **F-011 | OutboxStore.flush() never called** — `LedgerStateMachine` enqueues events to `OutboxStore` but never calls `flush()`. Events are never written to RocksDB CF_OUTBOX. At-least-once delivery relies solely on in-memory Kafka producer buffer.
    → `ledger-core/.../LedgerStateMachine.java:393`, `rocksdb/OutboxStore.java:40`

11. **F-011 / F-005 | MySQL schema missing position** — `init.sql` `journal_line` table has no `position` column. `account_balance` UK is `(account_id, balance_type, currency)` without position. `ProjectionConsumer` does not pass position to mappers. Position-specific balances collapse or fail in View Layer.
    → `init.sql:19`, `ProjectionConsumer.java:98`

12. **F-001 | BalanceTypeConfig model severely incomplete** — Missing fields: `zeroFloorEnforce`, `overdrawnAlertThreshold`, `creditLimit`, `displayName`, `description`, `category`, `effectiveFrom/To`, `compositionLogic`, `formula`, `currencyScope`, `fxRevaluationEnabled`, `visibilityScope`, `queryableByClient`, `requiredPermissions`, `monitoringEnabled`, `alertRules`, `snapshotEnabled`, `snapshotFrequency`, `cacheEnabled`, `cacheTtlSeconds`, `createdBy`, `lastModifiedBy`, `changeReason`.
    → `ledger-core/.../domain/model/BalanceTypeConfig.java:5`

13. **F-001 | No admin REST endpoints for Registry** — No controller exists for POST/PUT/PATCH/GET `/admin/ledger/balance-types`. Registry is in-memory only with no DB persistence or history tracking.
    → missing file

14. **F-003 | Draft stored in-memory only** — `AdjustmentService` uses `ConcurrentHashMap<String, AdjustmentDraft>`. Requirement specifies MySQL `adjustments_draft` table. Drafts lost on restart.
    → `ledger-service/.../AdjustmentService.java:20`

15. **F-009 | EOD trigger is placeholder** — `AccountingPeriodService.triggerEOD()` marks status CLOSING then immediately CLOSED. Steps 2-9 (drain queue, snapshot, recon, report) are commented placeholders.
    → `ledger-service/.../AccountingPeriodService.java:28`

16. **F-007 | Reconciliation L1 endpoint stubbed** — `ReconciliationController.runL1()` returns static message. Does not fetch journals by date or invoke real L1 logic. Case management APIs (GET/PATCH cases, file upload) missing.
    → `ledger-restful/.../ReconciliationController.java:28`

17. **F-005 | As-of balance not implemented** — `BalanceQueryService.getAsOfBalance()` falls back to current balance. Requirement: query MySQL View Layer snapshot or journal replay.
    → `ledger-service/.../BalanceQueryService.java:92`

18. **F-010 | Account query endpoints missing** — No GET `/ledger/accounts/{accountId}` or query by type/owner. `AccountController` only has create/freeze/unfreeze/close/addBalanceType.
    → missing file / controller methods

### 🟡 Warnings — Design / Compliance / Testability

19. **F-002 / F-003 | PostingCommand.Line has no amount field** — Amount lives at leg level only. While RFQ scenario works, this limits flexibility for multi-line legs with differing amounts. Spec shows amount per JournalLine.
    → `ledger-core/.../command/PostingCommand.java:32`

20. **F-008 / F-013 | Account queue backpressure not wired** — `AccountQueueManager.submit()` returns false on full queue, but controllers never check it. No HTTP 429 QUEUE_FULL returned.
    → `ledger-core/.../queue/AccountQueueManager.java:38`

21. **F-010 | createAccount hardcodes position=CURRENT** — `applyAccountCreate` initializes all balances with `"CURRENT"`. Spec says balanceInitializations should include position. Controller does not parse position from request.
    → `ledger-core/.../LedgerStateMachine.java:433`

22. **F-002 | PostingController uses raw Map deserialization** — No DTO or Bean Validation. Manual casting fragile. Missing V-01 (requestId format), V-03 (balanceType ACTIVE), V-04 (amount>0) at network layer.
    → `ledger-restful/.../PostingController.java:34`

23. **NFR / Observability | No Prometheus metrics** — No Micrometer counters/histograms for posting duration, balance query duration, queue depth, rejected count, etc.
    → missing instrumentation

24. **NFR / Observability | Structured logging incomplete** — Controllers and services lack required JSON fields: traceId, spanId, operationType, durationMs, outcome, errorCode, operator.
    → missing `@StructuredArguments`

25. **F-011b | PostingCompletionEvent not published** — `ProjectionConsumer` only listens to `ledger.account.v1` and `ledger.balance.change.v1`. No completion event topic or publisher.
    → missing

26. **ADR-001 | Learner node not implemented** — No separate Raft Learner process. ProjectionConsumer reads Kafka instead of Raft Log. Learner-to-MySQL direct sync not implemented.
    → missing

### 🔵 Notes — Minor / Style

27. **F-008 | Snapshot uses JSON not Protobuf/Kryo** — Functional but larger and slower than spec recommendation. Acceptable for MVP.
28. **F-004 | Reversal P95 target 5ms** — Global synchronized lock on applyPosting/applyReversal makes this impossible under concurrent load.
29. **F-002 | Seed restriction logic** — Code allows single-line legs for COMPANY/NOSTRO/SUSPENSE only. This is a business rule not documented in the spec; verify if intentional.

---

**Gap Count**: 18 🔴 | 9 🟡 | 3 🔵

**Most severe 🔴 (stop-ship)**:
- Unbalanced journal can post (#1)
- No durable idempotency (#5)
- No atomic RocksDB write in apply path (#2)
- Account queue bypassed / global lock (#3, #4)
- Adjustment indistinguishable from normal posting (#6)
- MySQL schema missing position (#11)

Next: Fix #1-#6 and #11 before dispatching ledger-test-writer. Recommend hotfix pipeline for #1, #2, #5, #6.

---

## [2026-05-24 00:35] Step 1 — ledger-requirements (F-014 Client SDK)
Status: ✅ PASS
Summary: F-014 spec added to LEDGER-PLATFORM-FULL-REQUIREMENTS.md (v0.6). TDD-TEST-CASES.md updated (v0.5) with 8 TC-F014-xx cases and Phase 7 execution plan.
Findings: none
Next: software-architect (ADR-002)

---

## [2026-05-24 00:42] Step 2 — software-architect (ADR-002 Client SDK)
Status: ✅ PASS
Summary: ADR-002 created at requirement/ADR-002-client-sdk.md. ADR-001 updated (v0.5) to include Client SDK Layer in §2.3 architecture diagram and F-014 in impact scope.
Findings: none
Next: Implementation (ledger-client-sdk module + pom.xml update). No dedicated coding agent in roster.

---

## [2026-05-24 00:50] Step 3 — java-dev (generic agent)
Status: ✅ PASS
Summary: ledger-client-sdk module created with 5 classes. Root pom.xml updated. mvn clean compile -pl ledger-client-sdk -am SUCCESS.
Findings: 🟡 LedgerHttpTransport.java uses deprecated API (non-blocking)
Next: ledger-reviewer

---

## [2026-05-24 00:55] Step 4 — ledger-reviewer
Status: ✅ PASS
Summary: Reviewed ledger-client-sdk diff. 0 🔴, 4 🟡, 4 🔵. 🟡 items: DEBUG/WARN body logging risks, read retry on 4xx, 503 retry logic gap. No blockers.
Findings:
- LedgerHttpTransport.java:67 🟡 DEBUG logs response body (PII risk)
- LedgerHttpTransport.java:48-49 🟡 WARN logs response body on HTTP >=400
- LedgerClient.java:145-161 🟡 Read retries on 4xx due to IO_ERROR wrapping
- LedgerHttpTransport.java:60-73 🟡 503 retry trigger only checks body string, not status code
Next: ledger-test-writer

---

## [2026-05-24 12:25] Step 5 — ledger-test-writer (F-014 tests)
Status: ✅ PASS
Summary: 9 JUnit 5 tests written for ledger-client-sdk (TC-F014-01~08). mvn test -pl ledger-client-sdk -am GREEN (9 passed, 0 failed). LedgerCore tests also green (50 passed).
Findings: none
Next: ops-sre (Docker / Postman / smoke-test.sh update)

---

## [2026-05-24 12:30] Step 6 — ops-sre (F-014 operational artifacts)
Status: ✅ PASS
Summary: docker-compose up --build completed successfully. All nodes (8081/8082/8083) report UP. Postman collection updated with /raft/leader. ledger-perf-tests module skeleton created. Fixed ProjectionIntegrationTest compilation error (missing position param in insertJournalLine call).
Findings:
- Postman: added /raft/leader endpoint tagged TC-F014-04
- ledger-perf-tests: module skeleton ready; Java source pending
- ProjectionIntegrationTest: fixed position arg gap
Next: smoke-tester

---

## [2026-05-24 12:35] Step 7 — smoke-tester
Status: ✅ PASS
Summary: smoke-test.sh executed against localhost:8081. 10/10 passed (health, create account, deposit, balance query, withdrawal, journal query, idempotency). No failures.
Findings: none
---

## [2026-05-24 12:40] Step 8-9 — qa-engineer + final summary
Status: ✅ PASS
Summary: ledger-perf-tests Java source implemented (LedgerStressTest with RFQ, race, read/write phases using LedgerClient). Assembly plugin builds executable fat jar. scripts/stress-test.sh updated to invoke jar for Phase 2, 4, 7. Docker rebuilt with LEDGER_ADVERTISE_URL env vars and RaftLeaderController fix. Sanity run confirms SDK leader discovery + failover works end-to-end. Smoke tests remain green.
Findings:
- ledger-perf-tests: 3 load phases implemented (rfq, race, readwrite)
- RaftLeaderController: added LEDGER_ADVERTISE_URL to return externally reachable leader URL
- stress-test.sh: replaced curl-based Phase 2/4/7 with java -jar invocations
Next: PIPELINE COMPLETE for F-014 Client SDK + perf module integration.

---

## [2026-05-24 01:00] Step 5 — ledger-test-writer
Status: ⏳ IN PROGRESS
Summary: Dispatching ledger-test-writer to implement TC-F014-01~08 for ledger-client-sdk.
Findings: none
Next: ops-sre (after mvn test passes)


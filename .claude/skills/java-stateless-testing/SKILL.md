---
name: java-stateless-testing
description: >-
  Enforces stateless service design and high-coverage Java tests for ledger/HFT
  code. Use when writing tests, reviewing state handling, or when the user mentions
  stateless, 100% coverage, deterministic tests, or testability of hot paths.
---

# Java Stateless Design & Test Coverage

Services must be **stateless at the API layer**; durable state lives in Raft/WAL/snapshot only. Tests must prove behavior with high coverage without flakiness.

## Stateless Rules

- HTTP/RPC handlers: no mutable instance fields for request data; no static mutable caches without explicit concurrency doc.
- Per-request context on stack or thread-local scratch cleared after use — never leak across requests.
- Idempotency keys and sequence numbers come from client or Raft index — not hidden server counters on instance fields.
- Config immutable after startup; hot-reload via atomic reference swap, not mutating shared fields mid-request.

## Testing Hot Paths Without Defeating Performance

| Concern | Approach |
|---------|----------|
| Zero-GC hot path | Unit-test **codec/layout** and **state machine transitions** separately from I/O |
| mmap / Chronicle | Temp directory per test; `@TempDir`; close queue in `@AfterEach` |
| Concurrency | Deterministic tests with single-threaded executor first; stress tests separate, tagged `@Tag("slow")` |
| Time | Inject `Clock` or `NanoClock` interface — no `Thread.sleep` in unit tests |

## Coverage Expectations

- **100% line coverage** on: domain logic, codecs, state machine, validation.
- Infrastructure glue (Chronicle init, network bind): integration tests; exclude only with explicit `@Generated` or documented `@ExcludeFromJacoco` — justify in PR.
- Every public method: happy path + at least one failure/invariant violation.
- Branch coverage on error codes and boundary indices (empty log, first index, roll boundary).

## Test Structure

```text
ledger-core/
  src/test/java/.../unit/       # fast, no network, TempDir OK
  src/test/java/.../integration/ # multi-module, docker optional
```

- Prefer **interface mocks** at boundaries (`LogStorage`, `StateMachine`).
- Golden-file tests for binary wire format — byte[] expected, not string snapshots.
- Property-style or parameterized tests for index arithmetic and roll cycles.

## Anti-Patterns

- Tests that depend on execution order or shared static state.
- `@Disabled` without issue link and removal plan.
- Asserting only `not null` — assert exact indices, bytes, error codes.
- Integration test as sole coverage for pure functions.

## Review Checklist

- [ ] Handlers and services hold no per-request mutable instance state
- [ ] Clock and storage injected via interfaces
- [ ] Unit tests cover domain + codec with TempDir lifecycle
- [ ] Coverage report shows 100% on targeted packages
- [ ] No sleep-based timing assertions in unit tests

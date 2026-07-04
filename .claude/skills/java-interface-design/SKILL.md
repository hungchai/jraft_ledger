---
name: java-interface-design
description: >-
  Eliminates duplicate Java code via interface-first design, SPI factories, and
  shared contracts. Use when refactoring duplicated logic, adding storage/transport
  backends, designing extensibility, or when the user asks for DRY, interfaces,
  or pluggable implementations.
---

# Java Interface-First — No Duplicate Code

Prefer **one contract, many implementations**. Duplication is a design smell — extract before copying.

## When to Introduce an Interface

| Signal | Action |
|--------|--------|
| Two classes share method shapes | Extract interface + move shared signatures |
| Swap implementations (WAL, transport, codec) | Interface + factory/SPI |
| Test doubles needed | Interface at dependency boundary |
| Copy-paste block > 3 lines | Extract to interface default method **or** package-private helper — not both blindly |

Do **not** interface every class. Interface at **boundaries**: persistence, network, serialization, policy.

## Patterns (Preferred Order)

1. **Small interface, focused impl** — e.g. `LogStorage`, `WalEncoder`, `SnapshotStore`.
2. **Factory interface** — `LogStorageFactory create(String path, Options opts)`; wiring in DI/config only.
3. **Default methods** — shared one-liners only; heavy logic → package-private `*Support` class, not defaults.
4. **Composition over inheritance** — impl holds `Metrics`, `Clock` interfaces; no deep abstract class trees.
5. **Strategy via interface** — runtime selection without `if/else` chains duplicating call sites.

## DRY Rules

- **One canonical encode/decode** per wire format — impls delegate, never re-copy field layout.
- **Shared constants** in `*Constants` or interface `static final` — not duplicated across impls.
- **Validation once** at boundary; impls assume invariants hold.
- Refactor duplication **before** adding a third copy — rule of three.

## Interface Segregation

Split fat interfaces:

```java
// Bad: forces no-op methods
interface Storage { void append(); void snapshot(); void compact(); void metrics(); }

// Good: compose
interface AppendOnlyLog { long append(ByteBuffer record); }
interface SnapshotWriter { void writeSnapshot(long index, ByteBuffer state); }
```

Callers depend only on what they use.

## Project Conventions (Ledger / JRaft)

- Extend framework SPIs (`LogStorage`, `StateMachine`) rather than forking framework code.
- New WAL backend: implement SPI + factory; do not duplicate Raft log index logic in the impl.
- Config selects impl by name (`chronicle`, `rocksdb`) — no `new Concrete()` in business code.

## Anti-Patterns

- Copy-paste impl with "TODO unify later".
- God interface with 15 methods and half the impls throw `UnsupportedOperationException`.
- Abstract class with only one subclass — use concrete class or interface + delegation.
- Duplicating binary layout in two packages — extract `WireCodec` interface.

## Review Checklist

- [ ] No duplicated logic across two+ classes without shared contract
- [ ] Boundary crossed by interface + factory, not concrete type
- [ ] Interfaces are small; no unsupported method stubs
- [ ] Single source of truth for wire format / constants
- [ ] Tests mock interfaces, not concrete infra

---
name: java-zero-gc
description: >-
  Enforces near-zero GC on Java hot paths: off-heap reuse, thread-local scratch,
  primitive collections, and allocation-free control flow. Use when the user says
  no GC, zero allocation, low-latency ledger/trading code, or when reviewing
  performance-critical Java beyond Effective Java Item 6.
---

# Java Zero-GC Hot Path

Goal: **zero heap allocations per operation** on the critical path. Pair with `effective-java-item-6` for baseline allocation rules.

## Layer 1 — Eliminate Heap Churn (Item 6)

Apply all Item 6 rules: no duplicate strings/wrappers, cached Patterns/formatters, primitive loops, pre-sized collections, primitive maps (fastutil / Eclipse Collections).

## Layer 2 — Reuse Instead of Allocate

| Pattern | Use |
|---------|-----|
| Thread-local scratch | `ThreadLocal<byte[]>` or Chronicle `Bytes` reset per call — only when thread-bound |
| Flyweight / ring slot | Fixed array of mutable DTOs; index = sequence % capacity |
| Off-heap buffer pool | Pool **large** `DirectByteBuffer` or Agrona buffers — not lightweight DTOs |
| Reset, don't new | Clear counters/offsets; never `new` per message |

Never pool Strings, small DTOs, or boxed numbers — modern GC handles ephemeral objects; custom pools add complexity.

## Layer 3 — Off-Heap & Binary

- Encode/decode with `ByteBuffer`, Chronicle `Bytes`, or Agrona `UnsafeBuffer` — read/write at index, no intermediate objects.
- Prefer `long`/`int` bit-packing over small helper objects.
- Return status via primitives or error codes on hot path; defer rich exceptions to cold path.

## Layer 4 — Control Flow Without Allocation

Avoid on hot path:

- `Stream`, `Optional`, varargs, enhanced for-each over boxed collections
- Lambdas/anonymous classes that capture ( allocates `CapturedLambda`)
- `String.format`, regex, `split`, autoboxing in APIs (`Map<Long, Long>` → use `LongLongHashMap`)
- Defensive copies (`Arrays.copyOf`, `List.of`, immutable wrappers)

Prefer:

- Indexed `for (int i = 0; i < n; i++)` over iterators
- `switch` on primitives / enums (no switch expressions that box)
- Early return with primitive error codes

## Layer 5 — JVM & Measurement

- Measure with JFR allocation profiling or `-XX:+PrintGC` under load — prove zero alloc per op.
- ZGC/Shenandoah reduce pause impact but **do not** replace allocation avoidance on hot path.
- `-XX:+AlwaysPreTouch` for mapped/direct memory when startup latency trade-off is acceptable.

## Anti-Patterns

- "We'll use ZGC so allocations are fine" — wrong for HFT/ledger hot paths.
- Creating new `LogEntry`, `ByteBuffer.wrap(byte[])` per append when a reusable encoder exists.
- `Collectors.toList()` / `groupingBy` in request handlers.

## Review Checklist

- [ ] JFR shows no per-op heap allocation on hot path
- [ ] Thread-local or ring reuse documented; no unbounded retention
- [ ] Primitive collections for high-frequency maps/sets
- [ ] No Stream/Optional/lambda on critical path
- [ ] Exceptions and rich error objects only on cold path

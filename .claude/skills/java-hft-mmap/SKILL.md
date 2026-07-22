---
name: java-hft-mmap
description: >-
  Guides HFT-grade Java: memory-mapped I/O, off-heap persistence, deterministic
  low-latency paths, and firmware-like constraints. Use when implementing WAL,
  Chronicle Queue, FileChannel.map, DirectByteBuffer, lock-free hot paths, cache
  layout, or when the user mentions HFT, mmap, firmware, microsecond latency,
  or off-heap storage.
---

# Java HFT — mmap, Off-Heap, Firmware Discipline

Senior low-latency Java engineer. Optimize for predictable latency and minimal kernel/user copies — not average throughput alone.

## Memory-Mapped & Off-Heap I/O

Prefer mmap or off-heap for append-heavy persistence (WAL, event log, replay):

| Mechanism | When |
|-----------|------|
| `FileChannel.map` / `MappedByteBuffer` | Simple sequential read/write, OS page cache leverage |
| `DirectByteBuffer` + explicit `fsync` | Controlled flush, JNI boundary only |
| Chronicle Queue / Bytes / Wire | Structured append-only log, roll cycles, zero-copy reads |
| Agrona `UnsafeBuffer` | Ring buffers, IPC, custom binary layout |

Rules:

- Pre-size files or use roll cycles; avoid grow-in-hot-path.
- One writer thread per queue/segment when possible; readers use tailers, not shared mutable state.
- Encode fixed-width or length-prefixed binary; no JSON/XML on hot path.
- `SyncMode`: `SYNC` for durability, `ASYNC`/`NONE` only when loss window is acceptable.
- Unmap/remap on roll or truncate; document lifecycle (Chronicle handles most of this).

## Firmware-Like Determinism

Treat hot path like embedded firmware:

- **No surprises**: no reflection, no dynamic class load, no `Class.forName` in hot path.
- **Bounded work**: fixed ring buffers, capped batch sizes, no unbounded queues on critical path.
- **Pre-allocation**: buffers, collections, and scratch space allocated at init; hot path only resets indices.
- **Time**: prefer `System.nanoTime()` for latency measurement; avoid `Instant.now()` / `ZonedDateTime` in hot path.
- **Logging**: no `log.info` in hot path; sample or defer to async appender off critical thread.

## Concurrency for HFT

- Single-writer / multiple-reader beats general locking when domain allows it.
- `ReentrantLock` on write path only if multi-writer unavoidable; never hold lock across I/O flush.
- Lock-free (CAS, `VarHandle`, JCTools queues) for inter-thread handoff — profile before adopting.
- Avoid `synchronized` on hot path; avoid `ConcurrentHashMap` for single-threaded accumulators.
- Pin critical threads only when measured benefit; document CPU isolation assumptions.

## Cache & False Sharing

- Pad or `@Contended` fields updated by different threads (sequence counters, tail/head).
- Keep hot structs compact; separate read-mostly from write-heavy fields.
- Prefer sequential access patterns for mmap replay (CPU prefetch friendly).

## Anti-Patterns

- `InputStream`/`OutputStream` copy chains on latency-critical persistence.
- `String` concatenation or `StringBuilder` in encode/decode hot loop.
- Blocking `Future.get()` on request path; use pre-registered callbacks or ring-buffer dispatch.
- `Stream`, `Optional`, lambdas capturing heap objects in per-tick handlers.

## Review Checklist

- [ ] Persistence uses mmap/off-heap/Chronicle, not heap byte[] churn
- [ ] Hot path is bounded, pre-allocated, reflection-free
- [ ] Writer/reader threading model is explicit and minimal-lock
- [ ] Sync/durability mode matches product requirement
- [ ] No logging or datetime allocation on critical path

# Dispatch: state-machine-expert

**From**: ledger-orchestrator
**Task**: Fix non-deterministic journalId — derive from Raft iter.getIndex()
**Agent**: state-machine-expert (see `.claude/agents/state-machine-expert.md`)
**Dispatched**: 2026-06-01T14:32:33Z

## Context

## Root Cause: Non-deterministic journalId across Raft nodes

`LedgerStateMachine.applyJournalCommand()` uses local `AtomicLong` counters:

```java
long index = raftLogIndex.incrementAndGet();   // local counter
long seq   = journalSequence.incrementAndGet(); // local counter
String journalId = String.format("JNL-%04d", seq);
```

Same Raft entry on different nodes → different journalId. Leader's JNL-0001 may be USDT posting, follower's JNL-0001 may be BTC posting.

`restoreFromBytes()` resets counters:
```java
long journalCount = journalStore.size();
raftLogIndex.set(journalCount);    // detached from Raft log
journalSequence.set(journalCount); // detached from Raft log
```

## Fix

journalId must derive from Raft `iter.getIndex()` — the only deterministic sequence across nodes.

### Step 1: Inject raftIndex into RaftCommand

`CommandSerializer.deserialize()` already has bytes. Add `setRaftLogIndex(long)` to `RaftCommand` interface.

In `LedgerRaftStateMachine.onApply()` line 96-100:
```java
RaftCommand cmd = CommandSerializer.deserialize(bytes, len);
cmd.setRaftLogIndex(index);  // <-- inject Raft index
CommandResult result = executeCommand(cmd);
```

### Step 2: Replace local counters with cmd.raftLogIndex()

In `LedgerStateMachine.applyPosting()` line ~454:
```java
// Remove:
long index = raftLogIndex.incrementAndGet();
long seq = journalSequence.incrementAndGet();
String journalId = String.format("JNL-%04d", seq);

// Replace with:
long raftIndex = cmd.raftLogIndex();
String journalId = String.format("JNL-%016d", raftIndex);
```

Same pattern for `applyReversal()`, `applyAccountCreate()`, etc.

### Step 3: Remove local counters

Delete `raftLogIndex` and `journalSequence` AtomicLong fields from LedgerStateMachine. Remove counter reset from `restoreFromBytes()`. Remove `getRaftLogIndex()`, `getJournalSequence()`. Update callers that use these for monitoring to derive from Raft API instead.

### Step 4: Cleanup SnapshotData

Remove `raftLogIndex` and `journalSequence` from `SnapshotData` record. Remove their usage in `SnapshotData.from()` and `restoreTo()`.

## Files affected
- `ledger-core/.../raft/RaftCommand.java` — add `setRaftLogIndex(long)` + `getRaftLogIndex()`
- `ledger-core/.../command/PostingCommand.java` — implement `setRaftLogIndex()`
- `ledger-core/.../command/ReversalCommand.java` — implement `setRaftLogIndex()`
- `ledger-core/.../command/AccountCreateCommand.java` — implement `setRaftLogIndex()`
- `ledger-core/.../raft/LedgerRaftStateMachine.java` — inject index in `onApply()`
- `ledger-core/.../statemachine/LedgerStateMachine.java` — replace counters with cmd index

## Evidence

Test-cycle diagnostic shows:
```
leader: LA=15658 smJ=15556 USDT=100001200
node2:  LA=15658 smJ=15549 USDT=100000900  (7 journals behind, 3 trades diverged)
node3:  LA=15658 smJ=15556 USDT=100001200  (matches leader)
```

Same `lastAppliedIndex` → Raft log fully replicated. Different `smJournal` → local counters diverged.

## Status

Pending — awaiting agent pickup.

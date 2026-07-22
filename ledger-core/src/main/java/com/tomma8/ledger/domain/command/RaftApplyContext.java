package com.tomma8.ledger.domain.command;

import java.time.Instant;

/**
 * Raft log position for journal ID and balance index derivation.
 *
 * <p>Single-command entries use {@link #single(long)} (legacy {@code JNL-%016d} format).
 * Batch entries use {@link #batchEntry(long, int)} ({@code JNL-{index}-{sub}}).
 *
 * <p>{@code applyTimeMillis} is stamped once by the leader at submit and replicated
 * inside the log entry, so every node applies with the SAME wall-clock time. Apply
 * code must use {@link #applyTime()} instead of {@code Instant.now()} — calling the
 * local clock during apply makes journal/balance timestamps diverge across replicas.
 */
public record RaftApplyContext(long raftIndex, int subIndex, boolean batched, long applyTimeMillis) {

    private static final int SUB_INDEX_SCALE = 10_000;

    public static RaftApplyContext standalone() {
        return new RaftApplyContext(0, 0, false, System.currentTimeMillis());
    }

    public static RaftApplyContext single(long raftIndex) {
        return new RaftApplyContext(raftIndex, 0, false, System.currentTimeMillis());
    }

    public static RaftApplyContext single(long raftIndex, long applyTimeMillis) {
        return new RaftApplyContext(raftIndex, 0, false, applyTimeMillis);
    }

    public static RaftApplyContext batchEntry(long raftIndex, int subIndex) {
        return new RaftApplyContext(raftIndex, subIndex, true, System.currentTimeMillis());
    }

    public static RaftApplyContext batchEntry(long raftIndex, int subIndex, long applyTimeMillis) {
        return new RaftApplyContext(raftIndex, subIndex, true, applyTimeMillis);
    }

    /** Leader-stamped apply time — identical on every node (use instead of Instant.now()). */
    public Instant applyTime() {
        return Instant.ofEpochMilli(applyTimeMillis);
    }

    public boolean useRaftIndex() {
        return raftIndex > 0;
    }

    /** Monotonic logical index for balance entries and gap detection. */
    public long logicalIndex() {
        return useRaftIndex() ? raftIndex * SUB_INDEX_SCALE + subIndex : 0;
    }

    public String journalId(long localSeq) {
        if (!useRaftIndex()) {
            return String.format("JNL-%04d", localSeq);
        }
        if (batched) {
            return String.format("JNL-%016d-%04d", raftIndex, subIndex);
        }
        return String.format("JNL-%016d", raftIndex);
    }
}

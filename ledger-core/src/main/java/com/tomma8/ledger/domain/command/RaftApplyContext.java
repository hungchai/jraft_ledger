package com.tomma8.ledger.domain.command;

/**
 * Raft log position for journal ID and balance index derivation.
 *
 * <p>Single-command entries use {@link #single(long)} (legacy {@code JNL-%016d} format).
 * Batch entries use {@link #batchEntry(long, int)} ({@code JNL-{index}-{sub}}).
 */
public record RaftApplyContext(long raftIndex, int subIndex, boolean batched) {

    private static final int SUB_INDEX_SCALE = 10_000;

    public static RaftApplyContext standalone() {
        return new RaftApplyContext(0, 0, false);
    }

    public static RaftApplyContext single(long raftIndex) {
        return new RaftApplyContext(raftIndex, 0, false);
    }

    public static RaftApplyContext batchEntry(long raftIndex, int subIndex) {
        return new RaftApplyContext(raftIndex, subIndex, true);
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

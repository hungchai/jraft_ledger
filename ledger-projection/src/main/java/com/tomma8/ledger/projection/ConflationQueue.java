package com.tomma8.ledger.projection;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lock-free conflation queue for balance updates.
 *
 * Only the latest (highest accountSeq) update per key survives.
 * Uses {@link BalanceUpdate} as key — hashCode/equals on
 * (accountId, balanceType, currency).
 *
 * Single ConcurrentHashMap only — no separate ordering queue.
 * Drain iterates values directly. No race between put and enqueue.
 */
public class ConflationQueue {

    /** Latest update per (accountId, balanceType, currency). */
    private final ConcurrentHashMap<BalanceUpdate, BalanceUpdate> latest = new ConcurrentHashMap<>();

    private final LongAdder offered = new LongAdder();
    private final LongAdder conflated = new LongAdder();
    /** Approximate pending count (key count, not total offers). */
    private volatile int approxSize = 0;

    /**
     * Offer a balance update. If a newer update for the same key already
     * exists (higher accountSeq), this offer is dropped.
     */
    public void offer(BalanceUpdate bu) {
        offered.increment();
        BalanceUpdate prev = latest.put(bu, bu);
        if (prev == null) {
            approxSize++;
        } else if (prev.accountSeq() > bu.accountSeq()) {
            latest.put(bu, prev); // restore higher-seq
        } else {
            conflated.increment();
        }
    }

    /**
     * Drain up to {@code max} conflated updates into {@code out}.
     * Returns number drained. No ordering guarantee — concurrent-safe.
     */
    public int drainTo(List<BalanceUpdate> out, int max) {
        out.clear();
        int count = 0;
        var iter = latest.values().iterator();
        while (count < max && iter.hasNext()) {
            out.add(iter.next());
            iter.remove();
            count++;
        }
        approxSize = Math.max(0, approxSize - count);
        return count;
    }

    public int size() { return approxSize; }
    public boolean isEmpty() { return approxSize <= 0; }
    public long offeredCount() { return offered.sum(); }
    public long conflatedCount() { return conflated.sum(); }
}

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

    /**
     * Offer a balance update. If a newer update for the same key already
     * exists (higher accountSeq), this offer is dropped.
     *
     * <p>Uses compute() for atomic compare-and-swap: the entire
     * (check-seq, decide, update) sequence is irreversible and race-free.
     * The array hack captures the replacement decision out of the lambda.
     */
    public void offer(BalanceUpdate bu) {
        offered.increment();
        boolean[] replaced = {false};
        latest.compute(bu, (k, v) -> {
            if (v == null) {
                return bu;
            }
            if (v.accountSeq() > bu.accountSeq()) {
                return v; // keep newer
            }
            replaced[0] = true;
            return bu;
        });
        if (replaced[0]) {
            conflated.increment();
        }
    }

    /**
     * Drain up to {@code max} conflated updates into {@code out}.
     * Returns number drained. No ordering guarantee — concurrent-safe.
     *
     * <p>The compute callback only removes an entry if the value reference
     * is still the same one we observed — meaning a concurrent offer() for
     * the same key (which would replace the value) will prevent removal
     * and skip that entry. This ensures no update is lost to drain.
     */
    public int drainTo(List<BalanceUpdate> out, int max) {
        out.clear();
        int count = 0;
        var iter = latest.entrySet().iterator();
        while (count < max && iter.hasNext()) {
            var entry = iter.next();
            BalanceUpdate value = entry.getValue();
            boolean[] removed = {false};
            latest.compute(entry.getKey(), (k, v) -> {
                if (v == value) {
                    removed[0] = true;
                    out.add(value);
                    return null;
                }
                return v;
            });
            if (removed[0]) {
                count++;
            }
        }
        return count;
    }

    /** Drop all pending updates. Safe to call concurrently with offer/drain. */
    public void clear() {
        latest.clear();
        offered.reset();
        conflated.reset();
    }

    public int size() { return latest.size(); }
    public boolean isEmpty() { return latest.isEmpty(); }
    public long offeredCount() { return offered.sum(); }
    public long conflatedCount() { return conflated.sum(); }
}

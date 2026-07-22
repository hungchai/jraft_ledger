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

    /** Total offers received, including those later dropped or replaced. */
    private final LongAdder offered = new LongAdder();
    /** Offers that overwrote an existing pending update for the same key. */
    private final LongAdder conflated = new LongAdder();

    /**
     * Offer a balance update. If a newer update for the same key already
     * exists (higher accountSeq), this offer is dropped.
     *
     * <p>Allocation-free CAS loop instead of {@code compute()}: avoids the
     * per-call lambda + boolean[] capture on the hot path. {@code putIfAbsent}
     * and {@code replace(key, old, new)} are atomic, and the retry loop
     * resolves concurrent races without holding a bin lock across user code.
     */
    public void offer(BalanceUpdate bu) {
        offered.increment();
        for (;;) {
            BalanceUpdate prev = latest.putIfAbsent(bu, bu);
            if (prev == null) {
                return; // fresh insert, not a conflation
            }
            if (prev.accountSeq() > bu.accountSeq()) {
                return; // existing is newer, drop this offer
            }
            if (latest.replace(bu, prev, bu)) {
                conflated.increment();
                return; // overwrote an older pending update
            }
            // lost the race: another thread changed the entry, retry
        }
    }

    /**
     * Drain up to {@code max} conflated updates into {@code out}.
     * Returns number drained. No ordering guarantee — concurrent-safe.
     *
     * <p>Uses the atomic {@code remove(key, value)}: the entry is removed only
     * if it still maps to the exact value reference we observed. A concurrent
     * offer() that replaced the value makes the conditional remove fail, so the
     * entry survives to the next drain and no update is lost. Allocation-free —
     * no lambda or boolean[] capture per entry.
     */
    public int drainTo(List<BalanceUpdate> out, int max) {
        out.clear();
        int count = 0;
        var iter = latest.entrySet().iterator();
        while (count < max && iter.hasNext()) {
            var entry = iter.next();
            BalanceUpdate value = entry.getValue();
            if (latest.remove(entry.getKey(), value)) {
                out.add(value);
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

    /** Current number of pending (un-drained) updates. */
    public int size() { return latest.size(); }
    /** True when no updates are pending. */
    public boolean isEmpty() { return latest.isEmpty(); }
    /** Cumulative offers received since the last {@link #clear()}. */
    public long offeredCount() { return offered.sum(); }
    /** Cumulative conflated (overwritten) offers since the last {@link #clear()}. */
    public long conflatedCount() { return conflated.sum(); }
}

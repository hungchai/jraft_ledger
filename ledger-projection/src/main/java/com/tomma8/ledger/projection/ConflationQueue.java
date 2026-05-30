package com.tomma8.ledger.projection;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lock-free conflation queue for balance updates.
 *
 * Only the latest (highest accountSeq) update per key survives.
 * Uses {@link BalanceUpdate} as both key and value — hashCode/equals
 * on (accountId, balanceType, currency) avoid String allocation.
 *
 * Designed for ZGC: allocations limited to new-key insertions.
 * Existing-key updates are O(1) map replacements with zero new allocations
 * (HashMap Node reused). Drain uses pre-allocated {@link ArrayList}.
 */
public class ConflationQueue {

    /** Latest update per (accountId, balanceType, currency). */
    private final ConcurrentHashMap<BalanceUpdate, BalanceUpdate> latest = new ConcurrentHashMap<>();

    /** FIFO ordering of keys for fair drain. */
    private final ConcurrentLinkedQueue<BalanceUpdate> keyOrder = new ConcurrentLinkedQueue<>();

    private final LongAdder offered = new LongAdder();
    private final LongAdder conflated = new LongAdder();

    /**
     * Offer a balance update. If a newer update for the same key already
     * exists (higher accountSeq), this offer is silently dropped.
     *
     * Lock-free: uses {@link ConcurrentHashMap#put} + CAS-like logic.
     */
    public void offer(BalanceUpdate bu) {
        offered.increment();
        BalanceUpdate prev = latest.put(bu, bu);
        if (prev == null) {
            keyOrder.offer(bu);
        } else if (prev.accountSeq() > bu.accountSeq()) {
            latest.put(bu, prev); // restore higher-seq entry
        } else {
            conflated.increment();
        }
    }

    /**
     * Drain up to {@code max} conflated updates into {@code out}.
     * {@code out} is cleared before use. Returns number drained.
     */
    public int drainTo(List<BalanceUpdate> out, int max) {
        out.clear();
        int count = 0;
        BalanceUpdate key;
        while (count < max && (key = keyOrder.poll()) != null) {
            BalanceUpdate bu = latest.remove(key);
            if (bu != null) {
                out.add(bu);
                count++;
            }
        }
        return count;
    }

    public int size() { return keyOrder.size(); }
    public boolean isEmpty() { return keyOrder.isEmpty(); }
    public long offeredCount() { return offered.sum(); }
    public long conflatedCount() { return conflated.sum(); }
}

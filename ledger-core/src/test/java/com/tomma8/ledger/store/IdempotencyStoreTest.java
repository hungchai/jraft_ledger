package com.tomma8.ledger.store;

import com.tomma8.ledger.domain.model.IdempotencyEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("IdempotencyStore — bounded LRU")
class IdempotencyStoreTest {

    private static IdempotencyEntry entry(String id) {
        return IdempotencyEntry.completed(id, "JNL-" + id, Instant.now());
    }

    @Test
    @DisplayName("TC-F008-IDEM-01 store is bounded — never exceeds cap under unbounded inserts")
    void store_boundedByCap_doesNotGrowUnbounded() {
        IdempotencyStore store = new IdempotencyStore(100);
        for (int i = 0; i < 10_000; i++) {
            store.put("req-" + i, entry("req-" + i));
        }
        // Was an unbounded map (heap OOM under load); now capped.
        assertThat(store.size()).isLessThanOrEqualTo(100);
    }

    @Test
    @DisplayName("TC-F008-IDEM-02 recent requestId still dedups; evicted oldest is gone")
    void recentDedups_oldestEvicted() {
        IdempotencyStore store = new IdempotencyStore(100);
        for (int i = 0; i < 1_000; i++) {
            store.put("req-" + i, entry("req-" + i));
        }
        // Most-recent within the window: still present (dedup works).
        assertThat(store.get("req-999")).isPresent();
        // Long-evicted oldest: gone (no RocksDB fallback by design — replay safety).
        assertThat(store.get("req-0")).isEmpty();
    }
}

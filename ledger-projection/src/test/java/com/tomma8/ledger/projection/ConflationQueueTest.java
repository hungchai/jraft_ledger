package com.tomma8.ledger.projection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD test suite for {@link ConflationQueue}.
 *
 * Verifies:
 * - Single key conflation (highest accountSeq wins)
 * - Multi-key drain (all keys returned)
 * - Drain clears the queue
 * - Concurrent offer + drain race safety (no lost entries)
 * - Lower seq rejection
 */
class ConflationQueueTest {

    private static BalanceUpdate bu(String accountId, String currency, long seq) {
        return new BalanceUpdate(0, accountId, "AVAILABLE_BALANCE", currency,
                BigDecimal.ZERO, "CURRENT", seq, "JNL-0001");
    }

    @Test
    @DisplayName("TC-QUEUE-CONFLATE-01: highest accountSeq per key survives")
    void highestSeqSurvives() {
        var q = new ConflationQueue();
        var batch = new ArrayList<BalanceUpdate>();

        q.offer(bu("ACC-A", "USDT", 1));
        q.offer(bu("ACC-A", "USDT", 2));
        q.offer(bu("ACC-A", "USDT", 3));

        int drained = q.drainTo(batch, 100);
        assertThat(drained).isEqualTo(1);
        assertThat(batch.get(0).accountSeq()).isEqualTo(3);
    }

    @Test
    @DisplayName("TC-QUEUE-CONFLATE-02: lower seq is rejected")
    void lowerSeqRejected() {
        var q = new ConflationQueue();
        var batch = new ArrayList<BalanceUpdate>();

        q.offer(bu("ACC-A", "USDT", 5));
        q.offer(bu("ACC-A", "USDT", 2)); // lower seq → dropped

        int drained = q.drainTo(batch, 100);
        assertThat(drained).isEqualTo(1);
        assertThat(batch.get(0).accountSeq()).isEqualTo(5);
    }

    @Test
    @DisplayName("TC-QUEUE-CONFLATE-03: multiple keys all survive")
    void multipleKeysSurvive() {
        var q = new ConflationQueue();
        var batch = new ArrayList<BalanceUpdate>();

        q.offer(bu("ACC-A", "USDT", 1));
        q.offer(bu("ACC-B", "USDT", 1));
        q.offer(bu("ACC-C", "USDT", 1));
        q.offer(bu("ACC-A", "BTC", 1));
        q.offer(bu("ACC-B", "BTC", 1));

        int drained = q.drainTo(batch, 100);
        assertThat(drained).isEqualTo(5);
    }

    @Test
    @DisplayName("TC-QUEUE-CONFLATE-04: drain clears the queue completely")
    void drainClearsQueue() {
        var q = new ConflationQueue();
        var batch = new ArrayList<BalanceUpdate>();

        q.offer(bu("ACC-A", "USDT", 1));
        q.offer(bu("ACC-B", "USDT", 2));
        q.offer(bu("ACC-C", "USDT", 3));

        q.drainTo(batch, 100);
        assertThat(batch).hasSize(3);

        // Second drain should return 0
        batch.clear();
        int drained = q.drainTo(batch, 100);
        assertThat(drained).isZero();
        assertThat(batch).isEmpty();
        assertThat(q.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("TC-QUEUE-CONFLATE-05: mix of conflated + unique keys drains correctly")
    void mixedConflatedAndUnique() {
        var q = new ConflationQueue();
        var batch = new ArrayList<BalanceUpdate>();

        // ACC-A gets 5 updates → conflated to 1
        for (long s = 1; s <= 5; s++) {
            q.offer(bu("ACC-A", "USDT", s));
        }
        // ACC-B gets 3 updates → conflated to 1
        q.offer(bu("ACC-B", "USDT", 1));
        q.offer(bu("ACC-B", "USDT", 2));
        q.offer(bu("ACC-B", "USDT", 3));
        // ACC-C gets 1 update
        q.offer(bu("ACC-C", "USDT", 1));

        int drained = q.drainTo(batch, 100);
        assertThat(drained).isEqualTo(3);

        long seqA = batch.stream().filter(b -> b.accountId().equals("ACC-A")).findFirst().orElseThrow().accountSeq();
        long seqB = batch.stream().filter(b -> b.accountId().equals("ACC-B")).findFirst().orElseThrow().accountSeq();
        long seqC = batch.stream().filter(b -> b.accountId().equals("ACC-C")).findFirst().orElseThrow().accountSeq();

        assertThat(seqA).isEqualTo(5);
        assertThat(seqB).isEqualTo(3);
        assertThat(seqC).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-QUEUE-CONFLATE-06: drain respects max limit")
    void drainRespectsLimit() {
        var q = new ConflationQueue();
        var batch = new ArrayList<BalanceUpdate>();

        for (int i = 1; i <= 50; i++) {
            q.offer(bu("ACC-" + i, "USDT", 1));
        }

        int drained = q.drainTo(batch, 10);
        assertThat(drained).isEqualTo(10);
        assertThat(batch).hasSize(10);

        // Remaining should be drainable
        batch.clear();
        int drained2 = q.drainTo(batch, 100);
        assertThat(drained2).isEqualTo(40);
    }

    @RepeatedTest(10)
    @DisplayName("TC-QUEUE-CONFLATE-07: concurrent offer + drain — no entries lost")
    void concurrentOfferAndDrainNoLoss() throws InterruptedException {
        var q = new ConflationQueue();
        var totalOffered = new AtomicInteger(0);
        var totalDrained = new AtomicInteger(0);
        int numProducers = 4;
        int offersPerProducer = 400;

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numProducers + 1);

        // Producer threads
        for (int p = 0; p < numProducers; p++) {
            final int producerId = p;
            Thread.ofPlatform().start(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < offersPerProducer; i++) {
                        String acc = "ACC-" + (i % 50);
                        q.offer(bu(acc, "USDT", producerId * 1000L + i));
                        totalOffered.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Drainer thread
        Thread.ofPlatform().start(() -> {
            try {
                startLatch.await();
                var batch = new ArrayList<BalanceUpdate>();
                for (int cycle = 0; cycle < 20; cycle++) {
                    Thread.sleep(5);
                    batch.clear();
                    totalDrained.addAndGet(q.drainTo(batch, 200));
                }
                // Final drain
                Thread.sleep(20);
                batch.clear();
                totalDrained.addAndGet(q.drainTo(batch, 200));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        doneLatch.await();

        // After all producers done + final drain, queue should be empty
        assertThat(q.isEmpty()).isTrue();

        // Total drained should be ≤ 50 (max 50 unique keys at any time)
        // Each key conflated, so max keys = 50
        var batch = new ArrayList<BalanceUpdate>();
        q.drainTo(batch, 200);
        assertThat(batch).isEmpty();
    }

    @Test
    @DisplayName("TC-QUEUE-CONFLATE-08: mixed conflation — same key interleaved with others")
    void interleavedKeys() {
        var q = new ConflationQueue();
        var batch = new ArrayList<BalanceUpdate>();

        // Interleave: A, B, A, C, B, A — A should end at seq=3
        q.offer(bu("ACC-A", "USDT", 1));
        q.offer(bu("ACC-B", "USDT", 1));
        q.offer(bu("ACC-A", "USDT", 2));  // conflates A
        q.offer(bu("ACC-C", "USDT", 1));
        q.offer(bu("ACC-B", "USDT", 2));  // conflates B
        q.offer(bu("ACC-A", "USDT", 3));  // conflates A again

        int drained = q.drainTo(batch, 100);
        assertThat(drained).isEqualTo(3);

        assertThat(find(batch, "ACC-A").accountSeq()).isEqualTo(3);
        assertThat(find(batch, "ACC-B").accountSeq()).isEqualTo(2);
        assertThat(find(batch, "ACC-C").accountSeq()).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-QUEUE-CONFLATE-09: different currencies are different keys")
    void differentCurrenciesDifferentKeys() {
        var q = new ConflationQueue();
        var batch = new ArrayList<BalanceUpdate>();

        q.offer(bu("ACC-A", "USDT", 1));
        q.offer(bu("ACC-A", "BTC", 1));
        q.offer(bu("ACC-A", "USDT", 2));
        q.offer(bu("ACC-A", "BTC", 2));

        int drained = q.drainTo(batch, 100);
        assertThat(drained).isEqualTo(2);

        assertThat(find(batch, "ACC-A", "USDT").accountSeq()).isEqualTo(2);
        assertThat(find(batch, "ACC-A", "BTC").accountSeq()).isEqualTo(2);
    }

    @Test
    @DisplayName("TC-QUEUE-CONFLATE-10: massive conflation — 10000 offers, 10 keys")
    void massiveConflation() {
        var q = new ConflationQueue();
        var batch = new ArrayList<BalanceUpdate>();

        for (int i = 0; i < 10_000; i++) {
            String acc = "ACC-" + (i % 10);
            q.offer(bu(acc, "USDT", i));
        }

        int drained = q.drainTo(batch, 100);
        assertThat(drained).isEqualTo(10);

        // Each key should have its highest seq (last offer for that key)
        for (int k = 0; k < 10; k++) {
            String acc = "ACC-" + k;
            int expectedMax = -1;
            for (int i = 0; i < 10_000; i++) {
                if (i % 10 == k) expectedMax = i;
            }
            long actual = find(batch, acc).accountSeq();
            assertThat(actual).isEqualTo(expectedMax);
        }

        // Queue empty after full drain
        batch.clear();
        assertThat(q.drainTo(batch, 100)).isZero();
        assertThat(q.isEmpty()).isTrue();
    }

    // --- helpers ---

    private static BalanceUpdate find(List<BalanceUpdate> list, String accountId) {
        return list.stream()
                .filter(b -> b.accountId().equals(accountId))
                .findFirst().orElseThrow();
    }

    private static BalanceUpdate find(List<BalanceUpdate> list, String accountId, String currency) {
        return list.stream()
                .filter(b -> b.accountId().equals(accountId) && b.currency().equals(currency))
                .findFirst().orElseThrow();
    }
}

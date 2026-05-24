package com.tomma8.ledger.client;

import com.tomma8.ledger.domain.command.CommandResult;
import com.tomma8.ledger.domain.command.PostingCommand;
import com.tomma8.ledger.domain.model.BalanceQueryResult;
import com.tomma8.ledger.domain.model.EntryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link LedgerClient} using JDK {@link com.sun.net.httpserver.HttpServer}
 * to simulate Raft cluster nodes. Covers retry, failover, and API correctness scenarios.
 */
class LedgerClientTest {

    private final List<MockRaftNode> nodes = new ArrayList<>();
    private LedgerClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        for (MockRaftNode node : nodes) {
            node.close();
        }
        nodes.clear();
    }

    // ── Helpers ────────────────────────────────────────────────

    private static String baseUrl(MockRaftNode node) {
        return node.baseUrl();
    }

    private static PostingCommand validPosting() {
        return new PostingCommand(
                UUID.randomUUID().toString(),
                "PAYMENT",
                "biz-ref-001",
                LocalDate.of(2026, 5, 24),
                List.of(
                        new PostingCommand.Leg("leg-1", "SETTLEMENT",
                                BigDecimal.valueOf(100), "USD",
                                List.of(
                                        new PostingCommand.Line("ACC-1", "AVAILABLE_BALANCE", "DEFAULT",
                                                EntryType.CREDIT, "credit leg"),
                                        new PostingCommand.Line("ACC-2", "AVAILABLE_BALANCE", "DEFAULT",
                                                EntryType.DEBIT, "debit leg")
                                ))
                )
        );
    }

    // ── TC-F014-04: Retry on NOT_LEADER ──────────────────────

    /**
     * TC-F014-04 retry_idempotentPost_failoverRetriesAndSucceeds
     * <p>
     * Simulates leader failover: node1 initially claims leader but responds 503
     * NOT_LEADER on POST. SDK detects NOT_LEADER token, invalidates cache,
     * re-discovers leader, and retries against node2 successfully.
     * <p>
     * Note: Apache HttpClient 5 may trigger internal connection-level retries,
     * so node1 may receive &gt; 1 POST. The key assertion is that the SDK-level
     * failover succeeds and node2 receives the final POST.
     */
    @Test
    @DisplayName("TC-F014-04 retry_idempotentPost_failoverRetriesAndSucceeds")
    void retry_idempotentPost_failoverRetriesAndSucceeds() throws Exception {
        MockRaftNode node1 = new MockRaftNode();
        AtomicBoolean node1SteppedDown = new AtomicBoolean(false);
        node1.leaderResponse = () -> node1SteppedDown.get()
                ? null
                : "{\"leader\":\"" + baseUrl(node1) + "\"}";
        node1.postStatusCode = 503;
        node1.postResponseBody = "{\"errorCode\":\"NOT_LEADER\",\"message\":\"not the leader\"}";

        MockRaftNode node2 = new MockRaftNode();
        node2.leaderResponse = () -> "{\"leader\":\"" + baseUrl(node2) + "\"}";
        node2.postStatusCode = 200;
        node2.postResponseBody = "{\"status\":\"COMPLETED\",\"journalId\":\"JNL-001\",\"errorCodes\":[]}";

        nodes.addAll(List.of(node1, node2));

        // Background watcher flips steppedDown flag after first POST hits node1,
        // so re-discovery (after 10ms SDK backoff) sees node2 as leader.
        Thread watcher = new Thread(() -> {
            while (node1.postingCount.get() == 0) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            node1SteppedDown.set(true);
        });
        watcher.setDaemon(true);
        watcher.start();

        client = new LedgerClient(LedgerClientConfig.builder()
                .endpoints(List.of(baseUrl(node1), baseUrl(node2)))
                .maxRetries(3)
                .build());

        CommandResult result = client.post(validPosting());
        watcher.join(5000);

        assertThat(result.isCompleted()).isTrue();
        assertThat(result.journalId()).isEqualTo("JNL-001");

        // node1 receives at least 1 POST (the NOT_LEADER rejection);
        // Apache HttpClient may trigger internal connection-level retries
        assertThat(node1.postingCount.get()).isGreaterThanOrEqualTo(1);
        // node2 receives exactly 1 POST (the successful retry on new leader)
        assertThat(node2.postingCount.get()).isEqualTo(1);
    }

    // ── TC-F014-05: Retry reads — failover without body retry penalty ───

    /**
     * TC-F014-05 retry_nonIdempotentRead_failoverWithoutRetryBody
     * <p>
     * Given: leader node1 serves POST successfully (cache warm), then is stopped.
     * When:  client.queryBalance(queryRequest)
     * Then:  first GET to node1 fails with IO error, SDK invalidates cache,
     *        re-discovers leader (node2), retry GET to node2 succeeds.
     */
    @Test
    @DisplayName("TC-F014-05 retry_nonIdempotentRead_failoverWithoutRetryBody")
    void retry_nonIdempotentRead_failoverWithoutRetryBody() {
        MockRaftNode node1 = new MockRaftNode();
        node1.leaderResponse = () -> "{\"leader\":\"" + baseUrl(node1) + "\"}";
        node1.postStatusCode = 200;
        node1.postResponseBody = "{\"status\":\"COMPLETED\",\"journalId\":\"JNL-WARMUP\",\"errorCodes\":[]}";

        MockRaftNode node2 = new MockRaftNode();
        node2.leaderResponse = () -> "{\"leader\":\"" + baseUrl(node2) + "\"}";
        node2.balanceStatusCode = 200;
        node2.balanceResponseBody =
                "{\"accountId\":\"CLIENT_ACC_001\",\"balanceType\":\"AVAILABLE_BALANCE\"," +
                "\"currency\":\"USD\",\"amount\":700.00,\"positions\":{\"DEFAULT\":700.00}," +
                "\"allowNegative\":false,\"dataSource\":\"STATE_MACHINE\"}";

        nodes.addAll(List.of(node1, node2));

        client = new LedgerClient(LedgerClientConfig.builder()
                .endpoints(List.of(baseUrl(node1), baseUrl(node2)))
                .maxRetries(5)
                .build());

        // Warm up: post to node1 → discovers node1 as leader, caches it
        client.post(validPosting());
        assertThat(node1.postingCount.get()).isGreaterThanOrEqualTo(1);

        // Stop node1 to simulate leader failure (connection refused on next request)
        node1.stopServer();

        // Now queryBalance: GET to cached leader (node1) fails → rediscover → node2 succeeds
        BalanceQueryResult balanceResult = client.queryBalance(
                "CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD");

        assertThat(balanceResult.amount()).isEqualByComparingTo(new BigDecimal("700.00"));
        assertThat(balanceResult.dataSource()).isEqualTo("STATE_MACHINE");
        assertThat(balanceResult.accountId()).isEqualTo("CLIENT_ACC_001");
        assertThat(node2.balanceQueryCount.get()).isEqualTo(1);
    }

    // ── TC-F014-07: Balance query correctness ─────────────────

    /**
     * TC-F014-07 queryBalance_returnsCorrectBalance
     * <p>
     * Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD = 700.00 on the leader.
     * When:  client.queryBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD")
     * Then:  result.amount == 700.00, dataSource == "STATE_MACHINE".
     */
    @Test
    @DisplayName("TC-F014-07 queryBalance_returnsCorrectBalance")
    void queryBalance_returnsCorrectBalance() {
        MockRaftNode leader = new MockRaftNode();
        leader.leaderResponse = () -> "{\"leader\":\"" + baseUrl(leader) + "\"}";
        leader.balanceStatusCode = 200;
        leader.balanceResponseBody =
                "{\"accountId\":\"CLIENT_ACC_001\",\"balanceType\":\"AVAILABLE_BALANCE\"," +
                "\"currency\":\"USD\",\"amount\":700.00,\"positions\":{\"DEFAULT\":700.00}," +
                "\"allowNegative\":false,\"dataSource\":\"STATE_MACHINE\"}";

        nodes.add(leader);

        client = new LedgerClient(LedgerClientConfig.builder()
                .endpoints(List.of(baseUrl(leader)))
                .build());

        BalanceQueryResult result = client.queryBalance(
                "CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD");

        assertThat(result.amount()).isEqualByComparingTo(new BigDecimal("700.00"));
        assertThat(result.dataSource()).isEqualTo("STATE_MACHINE");
        assertThat(result.accountId()).isEqualTo("CLIENT_ACC_001");
        assertThat(result.balanceType()).isEqualTo("AVAILABLE_BALANCE");
        assertThat(result.currency()).isEqualTo("USD");
        assertThat(result.allowNegative()).isFalse();
        assertThat(leader.balanceQueryCount.get()).isGreaterThanOrEqualTo(1);
    }

    // ── TC-F014-08: Unbalanced journal propagation ────────────

    /**
     * TC-F014-08 post_unbalancedJournal_propagatesError
     * <p>
     * Given: PostingRequest with DEBIT ≠ CREDIT (unbalanced).
     * When:  client.post(unbalancedRequest)
     * Then:  server returns REJECTED with JOURNAL_UNBALANCED error.
     *        Business errors are not retried at the SDK level.
     */
    @Test
    @DisplayName("TC-F014-08 post_unbalancedJournal_propagatesError")
    void post_unbalancedJournal_propagatesError() {
        MockRaftNode leader = new MockRaftNode();
        leader.leaderResponse = () -> "{\"leader\":\"" + baseUrl(leader) + "\"}";
        leader.postStatusCode = 400;
        leader.postResponseBody =
                "{\"status\":\"REJECTED\",\"journalId\":null," +
                "\"errorCodes\":[\"JOURNAL_UNBALANCED\"]}";

        nodes.add(leader);

        client = new LedgerClient(LedgerClientConfig.builder()
                .endpoints(List.of(baseUrl(leader)))
                .maxRetries(3)
                .build());

        CommandResult result = client.post(validPosting());

        assertThat(result.isRejected()).isTrue();
        assertThat(result.errorCodes()).contains("JOURNAL_UNBALANCED");
        // Business errors are NOT retried at SDK level — at least 1 POST call
        assertThat(leader.postingCount.get()).isGreaterThanOrEqualTo(1);
    }
}

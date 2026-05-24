package com.tomma8.ledger.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Lightweight mock Raft node backed by JDK {@link HttpServer}.
 * Responds to /raft/leader, /ledger/postings, and /ledger/balances.
 * Tracks hit counts for test assertions.
 */
class MockRaftNode implements AutoCloseable {

    private final HttpServer server;
    final int port;
    final AtomicInteger leaderProbeCount = new AtomicInteger();
    final AtomicInteger postingCount = new AtomicInteger();
    final AtomicInteger balanceQueryCount = new AtomicInteger();

    /**
     * Dynamic leader response supplier — evaluated on each /raft/leader request.
     * Return null to simulate no-leader (HTTP 404).
     */
    volatile Supplier<String> leaderResponse;

    volatile int postStatusCode = 200;
    volatile String postResponseBody;
    volatile int balanceStatusCode = 200;
    volatile String balanceResponseBody;

    /** Fires after each POST response is sent (for test state mutation). */
    volatile Runnable onPostReceived;

    MockRaftNode() {
        try {
            server = HttpServer.create(new InetSocketAddress(0), 0);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create HttpServer", e);
        }
        port = server.getAddress().getPort();

        server.createContext("/raft/leader", this::handleLeaderProbe);
        server.createContext("/ledger/postings", this::handlePosting);
        server.createContext("/ledger/balances", this::handleBalanceQuery);
        server.setExecutor(null);
        server.start();
    }

    String baseUrl() {
        return "http://localhost:" + port;
    }

    void stopServer() {
        server.stop(0);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ── Handlers ──────────────────────────────────────────────

    private void handleLeaderProbe(HttpExchange exchange) throws IOException {
        leaderProbeCount.incrementAndGet();
        Supplier<String> supplier = leaderResponse;
        String body = supplier != null ? supplier.get() : null;
        if (body == null) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void handlePosting(HttpExchange exchange) throws IOException {
        postingCount.incrementAndGet();
        byte[] body = postResponseBody != null
                ? postResponseBody.getBytes(StandardCharsets.UTF_8)
                : "{}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(postStatusCode, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
        if (onPostReceived != null) {
            onPostReceived.run();
        }
    }

    private void handleBalanceQuery(HttpExchange exchange) throws IOException {
        balanceQueryCount.incrementAndGet();
        byte[] body = balanceResponseBody != null
                ? balanceResponseBody.getBytes(StandardCharsets.UTF_8)
                : "{}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(balanceStatusCode, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}

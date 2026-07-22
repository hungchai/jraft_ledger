package com.tomma8.ledger.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tomma8.ledger.domain.command.AdjustmentCommand;
import com.tomma8.ledger.domain.command.CommandResult;
import com.tomma8.ledger.domain.command.PostingCommand;
import com.tomma8.ledger.domain.command.ReversalCommand;
import com.tomma8.ledger.domain.model.BalanceQueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;

public class LedgerClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LedgerClient.class);

    private static final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();

    private final LedgerClientConfig config;
    private final LedgerHttpTransport transport;
    private final LeaderDiscoveryClient leaderDiscovery;

    public LedgerClient(LedgerClientConfig config) {
        this.config = config;
        this.transport = new LedgerHttpTransport(config);
        this.leaderDiscovery = new LeaderDiscoveryClient(config, transport, objectMapper);
    }

    // ── Sync methods ──────────────────────────────────────────

    public CommandResult post(PostingCommand request) {
        String json = toJson(request);
        String path = "/ledger/postings";
        String responseBody = executeWithRetry(() -> transport.postJson(currentLeader() + path, json));
        return parseCommandResult(responseBody);
    }

    public CommandResult reverse(ReversalCommand request) {
        String json = toJson(request);
        String path = "/ledger/journals/" + request.originalJournalId() + "/reversal";
        String responseBody = executeWithRetry(() -> transport.postJson(currentLeader() + path, json));
        return parseCommandResult(responseBody);
    }

    public CommandResult approveAdjustment(AdjustmentCommand request) {
        String json = toJson(request);
        String path = "/ledger/adjustments/drafts/" + request.draftId() + "/approve";
        String responseBody = executeWithRetry(() -> transport.postJson(currentLeader() + path, json));
        return parseCommandResult(responseBody);
    }

    public BalanceQueryResult queryBalance(String accountId, String balanceType, String currency) {
        String path = "/ledger/balances?accountId=" + urlEncode(accountId)
                + "&balanceType=" + urlEncode(balanceType)
                + "&currency=" + urlEncode(currency);
        String responseBody = executeRead(() -> transport.getJson(selectEndpoint() + path));
        return parseBalanceResult(responseBody);
    }

    // ── Async methods ─────────────────────────────────────────

    public CompletableFuture<CommandResult> postAsync(PostingCommand request) {
        return CompletableFuture.supplyAsync(() -> post(request), ForkJoinPool.commonPool());
    }

    public CompletableFuture<CommandResult> reverseAsync(ReversalCommand request) {
        return CompletableFuture.supplyAsync(() -> reverse(request), ForkJoinPool.commonPool());
    }

    public CompletableFuture<CommandResult> approveAdjustmentAsync(AdjustmentCommand request) {
        return CompletableFuture.supplyAsync(() -> approveAdjustment(request), ForkJoinPool.commonPool());
    }

    public CompletableFuture<BalanceQueryResult> queryBalanceAsync(String accountId, String balanceType, String currency) {
        return CompletableFuture.supplyAsync(() -> queryBalance(accountId, balanceType, currency), ForkJoinPool.commonPool());
    }

    // ── Internal ──────────────────────────────────────────────

    private String currentLeader() {
        return leaderDiscovery.getLeaderEndpoint();
    }

    private String selectEndpoint() {
        try {
            return leaderDiscovery.getLeaderEndpoint();
        } catch (LedgerClientException e) {
            return config.getEndpoints().get(0);
        }
    }

    @FunctionalInterface
    private interface HttpAction {
        String execute();
    }

    private String executeWithRetry(HttpAction action) {
        int maxRetries = config.getMaxRetries();
        int attempt = 0;
        long backoffMs = 10;

        while (true) {
            attempt++;
            try {
                String body = action.execute();
                if (isNotLeader(body)) {
                    leaderDiscovery.invalidateCache();
                    if (attempt > maxRetries) {
                        throw new LedgerClientException(LedgerClientException.MAX_RETRIES_EXCEEDED,
                                "Exceeded max retries (" + maxRetries + ") on NOT_LEADER");
                    }
                    log.info("Got NOT_LEADER, retry {}/{} after {}ms", attempt, maxRetries, backoffMs);
                    sleep(backoffMs);
                    backoffMs *= 2;
                    continue;
                }
                return body;
            } catch (LedgerClientException e) {
                if (e.getErrorCode().equals(LedgerClientException.IO_ERROR)) {
                    leaderDiscovery.invalidateCache();
                    if (attempt > maxRetries) {
                        throw new LedgerClientException(LedgerClientException.MAX_RETRIES_EXCEEDED,
                                "Exceeded max retries (" + maxRetries + ") after IO error: " + e.getMessage(), e);
                    }
                    log.info("IO error, retry {}/{} after {}ms: {}", attempt, maxRetries, backoffMs, e.getMessage());
                    sleep(backoffMs);
                    backoffMs *= 2;
                    continue;
                }
                throw e;
            }
        }
    }

    private String executeRead(HttpAction action) {
        int maxRetries = config.getMaxRetries();
        int attempt = 0;

        while (true) {
            attempt++;
            try {
                return action.execute();
            } catch (LedgerClientException e) {
                if (e.getErrorCode().equals(LedgerClientException.IO_ERROR) && attempt <= maxRetries) {
                    leaderDiscovery.invalidateCache();
                    continue;
                }
                throw e;
            }
        }
    }

    private boolean isNotLeader(String body) {
        return body != null && body.contains("\"NOT_LEADER\"");
    }

    private CommandResult parseCommandResult(String json) {
        try {
            return objectMapper.readValue(json, CommandResult.class);
        } catch (IOException e) {
            throw new LedgerClientException(LedgerClientException.SERIALIZATION_ERROR,
                    "Failed to parse CommandResult: " + e.getMessage(), e);
        }
    }

    private BalanceQueryResult parseBalanceResult(String json) {
        try {
            return objectMapper.readValue(json, BalanceQueryResult.class);
        } catch (IOException e) {
            throw new LedgerClientException(LedgerClientException.SERIALIZATION_ERROR,
                    "Failed to parse BalanceQueryResult: " + e.getMessage(), e);
        }
    }

    private static String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (IOException e) {
            throw new LedgerClientException(LedgerClientException.SERIALIZATION_ERROR,
                    "Failed to serialize " + obj.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    private static String urlEncode(String value) {
        if (value == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : value.toCharArray()) {
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c);
            } else {
                sb.append('%');
                sb.append(String.format("%02X", (int) c));
            }
        }
        return sb.toString();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        transport.close();
    }
}

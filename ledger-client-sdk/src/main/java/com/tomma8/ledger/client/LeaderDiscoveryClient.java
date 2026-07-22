package com.tomma8.ledger.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

class LeaderDiscoveryClient {

    private static final Logger log = LoggerFactory.getLogger(LeaderDiscoveryClient.class);

    private final LedgerClientConfig config;
    private final LedgerHttpTransport transport;
    private final ObjectMapper objectMapper;
    private final AtomicReference<CacheEntry> cache = new AtomicReference<>();

    LeaderDiscoveryClient(LedgerClientConfig config, LedgerHttpTransport transport, ObjectMapper objectMapper) {
        this.config = config;
        this.transport = transport;
        this.objectMapper = objectMapper;
    }

    String getLeaderEndpoint() {
        CacheEntry entry = cache.get();
        if (entry != null && !entry.isExpired(config.getLeaderCacheTtl())) {
            return entry.leader;
        }
        String leader = discoverLeader(config.getEndpoints());
        cache.set(new CacheEntry(leader, System.currentTimeMillis()));
        return leader;
    }

    void invalidateCache() {
        cache.set(null);
        log.debug("Leader cache invalidated");
    }

    String discoverLeader(List<String> endpoints) {
        for (String endpoint : endpoints) {
            String url = endpoint.endsWith("/") ? endpoint + "raft/leader" : endpoint + "/raft/leader";
            try {
                String body = transport.getJson(url);
                JsonNode node = objectMapper.readTree(body);
                JsonNode leaderField = node.get("leader");
                if (leaderField != null && !leaderField.isNull()) {
                    String leader = leaderField.asText();
                    log.info("Leader discovered at: {}", leader);
                    return leader;
                }
            } catch (LedgerClientException e) {
                log.debug("Probe {} failed: {}", url, e.getMessage());
            } catch (Exception e) {
                log.debug("Probe {} parse error: {}", url, e.getMessage());
            }
        }
        throw new LedgerClientException(LedgerClientException.NO_LEADER_AVAILABLE,
                "No leader found among endpoints: " + endpoints);
    }

    private record CacheEntry(String leader, long timestamp) {
        boolean isExpired(java.time.Duration ttl) {
            return System.currentTimeMillis() - timestamp > ttl.toMillis();
        }
    }
}

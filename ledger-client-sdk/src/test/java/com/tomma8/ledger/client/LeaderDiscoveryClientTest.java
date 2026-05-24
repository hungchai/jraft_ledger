package com.tomma8.ledger.client;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link LeaderDiscoveryClient}.
 * Uses mock {@link LedgerHttpTransport} to control endpoint probe responses.
 */
@ExtendWith(MockitoExtension.class)
class LeaderDiscoveryClientTest {

    @Mock
    private LedgerHttpTransport transport;

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            JsonMapper.builder().addModule(new JavaTimeModule()).build();

    private LedgerClientConfig config;
    private LeaderDiscoveryClient discoveryClient;

    @BeforeEach
    void setUp() {
        config = LedgerClientConfig.builder()
                .endpoints(List.of("http://node1:8001", "http://node2:8002", "http://node3:8003"))
                .leaderCacheTtl(Duration.ofHours(1)) // long TTL so cache doesn't expire
                .build();
        discoveryClient = new LeaderDiscoveryClient(config, transport, objectMapper);
    }

    /**
     * TC-F014-01 leaderDiscovery_firstCall_queriesAnyEndpoint
     * Given: no cached leader, 3 endpoints in config, only node2 returns a valid leader
     * When:  first call to discoverLeader()
     * Then:  probes endpoints; returns leader from node2
     */
    @Test
    @DisplayName("TC-F014-01 leaderDiscovery_firstCall_queriesAnyEndpoint")
    void leaderDiscovery_firstCall_queriesAnyEndpoint() {
        // node1: error (no leader)
        when(transport.getJson("http://node1:8001/raft/leader"))
                .thenThrow(new LedgerClientException(LedgerClientException.IO_ERROR, "node1 down"));
        // node2: leader found pointing to node3
        when(transport.getJson("http://node2:8002/raft/leader"))
                .thenReturn("{\"leader\":\"http://node3:8003\"}");
        // node3: never reached because we stop after finding leader

        String leader = discoveryClient.discoverLeader(config.getEndpoints());

        assertThat(leader).isEqualTo("http://node3:8003");
        verify(transport).getJson("http://node1:8001/raft/leader");
        verify(transport).getJson("http://node2:8002/raft/leader");
        verify(transport, never()).getJson("http://node3:8003/raft/leader");
    }

    /**
     * TC-F014-02 leaderDiscovery_cachedLeader_reusesHint
     * Given: leader hint already cached and TTL not expired
     * When:  100 consecutive calls to getLeaderEndpoint()
     * Then:  all use cached leader; no re-query to /raft/leader
     */
    @Test
    @DisplayName("TC-F014-02 leaderDiscovery_cachedLeader_reusesHint")
    void leaderDiscovery_cachedLeader_reusesHint() {
        // First call populates cache — needs one probe
        when(transport.getJson(anyString()))
                .thenReturn("{\"leader\":\"http://node1:8001\"}");

        String first = discoveryClient.getLeaderEndpoint();
        assertThat(first).isEqualTo("http://node1:8001");
        // Only one /raft/leader call during discovery
        verify(transport, times(1)).getJson(anyString());

        // 99 subsequent calls — must not probe again
        for (int i = 0; i < 99; i++) {
            String leader = discoveryClient.getLeaderEndpoint();
            assertThat(leader).isEqualTo("http://node1:8001");
        }

        // Still only 1 probe call total (cache hit every time)
        verify(transport, times(1)).getJson(anyString());
    }

    /**
     * TC-F014-03 leaderDiscovery_leaderStepDown_refreshesHint
     * Given: leader hint cached pointing to node1, cache not expired
     * When:  invalidateCache() is called (simulating NOT_LEADER response)
     * Then:  next getLeaderEndpoint() re-discovers; returns new leader
     */
    @Test
    @DisplayName("TC-F014-03 leaderDiscovery_leaderStepDown_refreshesHint")
    void leaderDiscovery_leaderStepDown_refreshesHint() {
        // Initial cache population — leader = node1
        when(transport.getJson("http://node1:8001/raft/leader"))
                .thenReturn("{\"leader\":\"http://node1:8001\"}");

        String first = discoveryClient.getLeaderEndpoint();
        assertThat(first).isEqualTo("http://node1:8001");
        verify(transport).getJson("http://node1:8001/raft/leader");

        // Simulate step-down: cache invalidated
        discoveryClient.invalidateCache();

        // New discovery after invalidation — node1 now refuses, node2 is new leader
        reset(transport);
        when(transport.getJson("http://node1:8001/raft/leader"))
                .thenThrow(new LedgerClientException(LedgerClientException.IO_ERROR, "refused"));
        when(transport.getJson("http://node2:8002/raft/leader"))
                .thenReturn("{\"leader\":\"http://node2:8002\"}");

        String second = discoveryClient.getLeaderEndpoint();
        assertThat(second).isEqualTo("http://node2:8002");
        verify(transport).getJson("http://node1:8001/raft/leader");
        verify(transport).getJson("http://node2:8002/raft/leader");
    }

    /**
     * No leader available — all probes fail.
     */
    @Test
    @DisplayName("discoverLeader_allEndpointsFail_throwsNoLeaderAvailable")
    void discoverLeader_allEndpointsFail_throwsNoLeaderAvailable() {
        when(transport.getJson(anyString()))
                .thenThrow(new LedgerClientException(LedgerClientException.IO_ERROR, "all down"));

        assertThatThrownBy(() -> discoveryClient.discoverLeader(config.getEndpoints()))
                .isInstanceOf(LedgerClientException.class)
                .extracting("errorCode")
                .isEqualTo(LedgerClientException.NO_LEADER_AVAILABLE);
    }
}

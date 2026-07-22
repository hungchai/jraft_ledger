package com.tomma8.ledger.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LedgerClientConfig} builder.
 */
class LedgerClientConfigTest {

    /**
     * TC-F014-06 config_allTimeoutsSettable
     * Given: LedgerClientConfig.builder() with custom timeouts, TTL, and maxRetries
     * When:  config.build()
     * Then:  all fields match the builder inputs
     */
    @Test
    @DisplayName("TC-F014-06 config_allTimeoutsSettable")
    void config_allTimeoutsSettable() {
        LedgerClientConfig config = LedgerClientConfig.builder()
                .endpoints(java.util.List.of("http://localhost:8080"))
                .connectTimeout(Duration.ofMillis(500))
                .readTimeout(Duration.ofMillis(3000))
                .leaderCacheTtl(Duration.ofSeconds(10))
                .maxRetries(5)
                .maxConnectionsPerRoute(25)
                .maxConnectionsTotal(100)
                .build();

        assertThat(config.getConnectTimeout()).isEqualTo(Duration.ofMillis(500));
        assertThat(config.getReadTimeout()).isEqualTo(Duration.ofMillis(3000));
        assertThat(config.getLeaderCacheTtl()).isEqualTo(Duration.ofSeconds(10));
        assertThat(config.getMaxRetries()).isEqualTo(5);
        assertThat(config.getMaxConnectionsPerRoute()).isEqualTo(25);
        assertThat(config.getMaxConnectionsTotal()).isEqualTo(100);
    }
}

package com.tomma8.ledger.client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class LedgerClientConfig {

    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(2);
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration DEFAULT_LEADER_CACHE_TTL = Duration.ofSeconds(5);
    public static final int DEFAULT_MAX_RETRIES = 3;
    public static final int DEFAULT_MAX_CONNECTIONS_PER_ROUTE = 50;
    public static final int DEFAULT_MAX_CONNECTIONS_TOTAL = 200;

    private final List<String> endpoints;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final Duration leaderCacheTtl;
    private final int maxRetries;
    private final int maxConnectionsPerRoute;
    private final int maxConnectionsTotal;

    private LedgerClientConfig(Builder builder) {
        this.endpoints = Collections.unmodifiableList(new ArrayList<>(builder.endpoints));
        this.connectTimeout = builder.connectTimeout;
        this.readTimeout = builder.readTimeout;
        this.leaderCacheTtl = builder.leaderCacheTtl;
        this.maxRetries = builder.maxRetries;
        this.maxConnectionsPerRoute = builder.maxConnectionsPerRoute;
        this.maxConnectionsTotal = builder.maxConnectionsTotal;
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<String> getEndpoints() { return endpoints; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public Duration getLeaderCacheTtl() { return leaderCacheTtl; }
    public int getMaxRetries() { return maxRetries; }
    public int getMaxConnectionsPerRoute() { return maxConnectionsPerRoute; }
    public int getMaxConnectionsTotal() { return maxConnectionsTotal; }

    public static final class Builder {
        private List<String> endpoints = List.of();
        private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        private Duration readTimeout = DEFAULT_READ_TIMEOUT;
        private Duration leaderCacheTtl = DEFAULT_LEADER_CACHE_TTL;
        private int maxRetries = DEFAULT_MAX_RETRIES;
        private int maxConnectionsPerRoute = DEFAULT_MAX_CONNECTIONS_PER_ROUTE;
        private int maxConnectionsTotal = DEFAULT_MAX_CONNECTIONS_TOTAL;

        private Builder() {}

        public Builder endpoints(List<String> endpoints) {
            this.endpoints = Objects.requireNonNull(endpoints, "endpoints must not be null");
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout must not be null");
            return this;
        }

        public Builder readTimeout(Duration readTimeout) {
            this.readTimeout = Objects.requireNonNull(readTimeout, "readTimeout must not be null");
            return this;
        }

        public Builder leaderCacheTtl(Duration leaderCacheTtl) {
            this.leaderCacheTtl = Objects.requireNonNull(leaderCacheTtl, "leaderCacheTtl must not be null");
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            if (maxRetries < 0) throw new IllegalArgumentException("maxRetries must be >= 0");
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder maxConnectionsPerRoute(int maxConnectionsPerRoute) {
            if (maxConnectionsPerRoute < 1) throw new IllegalArgumentException("maxConnectionsPerRoute must be >= 1");
            this.maxConnectionsPerRoute = maxConnectionsPerRoute;
            return this;
        }

        public Builder maxConnectionsTotal(int maxConnectionsTotal) {
            if (maxConnectionsTotal < 1) throw new IllegalArgumentException("maxConnectionsTotal must be >= 1");
            this.maxConnectionsTotal = maxConnectionsTotal;
            return this;
        }

        public LedgerClientConfig build() {
            if (endpoints.isEmpty()) {
                throw new IllegalArgumentException("endpoints must not be empty");
            }
            return new LedgerClientConfig(this);
        }
    }
}

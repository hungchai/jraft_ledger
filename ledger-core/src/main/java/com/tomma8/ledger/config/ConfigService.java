package com.tomma8.ledger.config;

/**
 * Centralized config access interface.
 *
 * Abstracts all external configuration sources (env vars, application.yml,
 * Apollo, Nacos, etc.) behind a single typed API.
 *
 * Why:
 * - Raw System.getenv() in LedgerConfig ties config to OS env vars only.
 * - ConfigService lets Spring @Value resolve application.yml + env vars
 *   + command-line args in one pass.
 * - Apollo / Nacos can be dropped in later by swapping the implementation
 *   — zero changes to call sites.
 *
 * Implementation: SpringConfigService (ledger-restful) reads @Value which
 * merges env vars, application.yml, and command-line overrides in Spring's
 * standard property resolution order.
 */
public interface ConfigService {
    String get(String key, String def);
    int getInt(String key, int def);
    long getLong(String key, long def);
    boolean getBool(String key, boolean def);
}

package com.tomma8.ledger.projection;

/**
 * Shard index for journal_line / projection_event_log physical tables.
 * Must match ShardingSphere INLINE rule in sharding-config.yaml:
 * {@code ${Math.abs(account_account_id.hashCode()) % 4}}
 */
public final class ShardRouting {

    public static final int SHARD_COUNT = 4;

    private ShardRouting() {}

    public static int shardIndex(String accountAccountId) {
        if (accountAccountId == null || accountAccountId.isEmpty()) return 0;
        return Math.floorMod(accountAccountId.hashCode(), SHARD_COUNT);
    }
}

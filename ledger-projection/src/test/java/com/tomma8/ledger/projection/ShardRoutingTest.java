package com.tomma8.ledger.projection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ShardRouting")
class ShardRoutingTest {

    @Test
    @DisplayName("shard index matches ShardingSphere INLINE expression")
    void shardIndex_matchesInlineExpression() {
        String[] ids = {"STRESS-HOT-CO-001", "STRESS-CLI-0001", "STRESS-CLI-0042", "ACC_X"};
        for (String id : ids) {
            int expected = Math.floorMod(id.hashCode(), 4);
            assertThat(ShardRouting.shardIndex(id)).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("shard index is always 0..3")
    void shardIndex_inRange() {
        for (int i = 0; i < 100; i++) {
            int shard = ShardRouting.shardIndex("ACC-" + i);
            assertThat(shard).isBetween(0, 3);
        }
    }
}

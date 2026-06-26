package com.tomma8.ledger.rest.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SpringConfigServiceTest {

  @Test
  void resolvesLegacyEnvKeyFromYmlProperty() {
    MockEnvironment env = new MockEnvironment();
    env.setProperty("ledger.rocksdb.path", "/custom/path");
    SpringConfigService cs = new SpringConfigService(env);
    assertEquals("/custom/path", cs.get("LEDGER_ROCKSDB_PATH", "default"));
  }

  @Test
  void resolvesPropertyKeyDirectly() {
    MockEnvironment env = new MockEnvironment();
    env.setProperty("ledger.rocksdb.path", "/custom/path");
    SpringConfigService cs = new SpringConfigService(env);
    assertEquals("/custom/path", cs.get("ledger.rocksdb.path", "default"));
  }

  @Test
  void fallsBackToDefaultWhenUnset() {
    MockEnvironment env = new MockEnvironment();
    SpringConfigService cs = new SpringConfigService(env);
    assertEquals("fallback", cs.get("kafka.bootstrap.servers", "fallback"));
  }

  @Test
  void getIntReadsTypedProperty() {
    MockEnvironment env = new MockEnvironment();
    env.setProperty("outbox.batch-size", "250");
    SpringConfigService cs = new SpringConfigService(env);
    assertEquals(250, cs.getInt("OUTBOX_BATCH_SIZE", 100));
  }

  @Test
  void getBoolReadsTypedProperty() {
    MockEnvironment env = new MockEnvironment();
    env.setProperty("ledger.kafka.required", "true");
    SpringConfigService cs = new SpringConfigService(env);
    assertTrue(cs.getBool("LEDGER_KAFKA_REQUIRED", false));
    env.setProperty("ledger.kafka.required", "false");
    assertFalse(cs.getBool("LEDGER_KAFKA_REQUIRED", true));
  }
}

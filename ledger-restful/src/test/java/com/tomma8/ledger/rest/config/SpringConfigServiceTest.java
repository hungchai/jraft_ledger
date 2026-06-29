package com.tomma8.ledger.rest.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tomma8.ledger.rest.config.properties.DataSourceConnectionProperties;
import com.tomma8.ledger.rest.config.properties.LedgerProperties;
import com.tomma8.ledger.rest.config.properties.OutboxProperties;
import com.tomma8.ledger.rest.config.properties.ServerPortProperties;
import org.junit.jupiter.api.Test;

class SpringConfigServiceTest {

  @Test
  void resolvesLegacyEnvKeyFromProperties() {
    LedgerProperties ledger = new LedgerProperties();
    ledger.getRocksdb().setPath("/custom/path");
    SpringConfigService cs = service(ledger, new OutboxProperties());
    assertEquals("/custom/path", cs.get("LEDGER_ROCKSDB_PATH", "default"));
  }

  @Test
  void resolvesPropertyKeyDirectly() {
    LedgerProperties ledger = new LedgerProperties();
    ledger.getRocksdb().setPath("/custom/path");
    SpringConfigService cs = service(ledger, new OutboxProperties());
    assertEquals("/custom/path", cs.get("ledger.rocksdb.path", "default"));
  }

  @Test
  void fallsBackToDefaultWhenUnset() {
    SpringConfigService cs = service(new LedgerProperties(), new OutboxProperties());
    assertEquals("fallback", cs.get("kafka.bootstrap.servers", "fallback"));
  }

  @Test
  void getIntReadsTypedProperty() {
    OutboxProperties outbox = new OutboxProperties();
    outbox.setBatchSize(250);
    SpringConfigService cs = service(new LedgerProperties(), outbox);
    assertEquals(250, cs.getInt("OUTBOX_BATCH_SIZE", 100));
  }

  @Test
  void getBoolReadsTypedProperty() {
    LedgerProperties ledger = new LedgerProperties();
    ledger.getKafka().setRequired(true);
    SpringConfigService cs = service(ledger, new OutboxProperties());
    assertTrue(cs.getBool("LEDGER_KAFKA_REQUIRED", false));
    ledger.getKafka().setRequired(false);
    assertFalse(cs.getBool("LEDGER_KAFKA_REQUIRED", true));
  }

  private static SpringConfigService service(LedgerProperties ledger, OutboxProperties outbox) {
    return new SpringConfigService(ledger, outbox, new DataSourceConnectionProperties(), new ServerPortProperties());
  }
}

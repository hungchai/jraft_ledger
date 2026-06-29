package com.tomma8.ledger.rest.config;

import com.tomma8.ledger.rest.config.properties.DataSourceConnectionProperties;
import com.tomma8.ledger.rest.config.properties.LedgerProperties;
import com.tomma8.ledger.rest.config.properties.OutboxProperties;
import com.tomma8.ledger.rest.config.properties.ServerPortProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        LedgerProperties.class,
        OutboxProperties.class,
        DataSourceConnectionProperties.class,
        ServerPortProperties.class
})
public class LedgerPropertiesConfiguration {
}

package com.tomma8.ledger.projection.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ProjectionLedgerProperties.class)
public class ProjectionPropertiesConfiguration {
}

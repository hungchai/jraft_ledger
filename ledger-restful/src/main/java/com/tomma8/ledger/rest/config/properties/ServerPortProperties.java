package com.tomma8.ledger.rest.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds {@code server.port} for {@link com.tomma8.ledger.rest.config.SpringConfigService}. */
@ConfigurationProperties(prefix = "server")
public class ServerPortProperties {

    private int port = 8080;

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
}

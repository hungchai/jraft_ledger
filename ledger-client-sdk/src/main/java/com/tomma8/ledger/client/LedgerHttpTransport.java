package com.tomma8.ledger.client;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

class LedgerHttpTransport implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LedgerHttpTransport.class);

    private final CloseableHttpClient httpClient;

    LedgerHttpTransport(LedgerClientConfig config) {
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setDefaultMaxPerRoute(config.getMaxConnectionsPerRoute());
        cm.setMaxTotal(config.getMaxConnectionsTotal());

        ConnectionConfig connConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.of(config.getConnectTimeout().toMillis(), TimeUnit.MILLISECONDS))
                .setSocketTimeout(Timeout.of(config.getReadTimeout().toMillis(), TimeUnit.MILLISECONDS))
                .build();
        cm.setDefaultConnectionConfig(connConfig);

        this.httpClient = HttpClients.custom()
                .setConnectionManager(cm)
                .build();
    }

    String getJson(String url) {
        HttpGet get = new HttpGet(url);
        try (CloseableHttpResponse response = httpClient.execute(get)) {
            int status = response.getCode();
            String body = EntityUtils.toString(response.getEntity());
            if (status >= 400) {
                log.warn("GET {} returned HTTP {}: {}", url, status, body);
                throw new LedgerClientException(LedgerClientException.IO_ERROR,
                        "HTTP " + status + " from GET " + url);
            }
            return body;
        } catch (IOException | ParseException e) {
            throw new LedgerClientException(LedgerClientException.IO_ERROR,
                    "GET " + url + " failed: " + e.getMessage(), e);
        }
    }

    String postJson(String url, String jsonBody) {
        HttpPost post = new HttpPost(url);
        post.setEntity(new StringEntity(jsonBody, ContentType.APPLICATION_JSON));
        try (CloseableHttpResponse response = httpClient.execute(post)) {
            int status = response.getCode();
            String body = EntityUtils.toString(response.getEntity());
            if (status == 503) {
                log.debug("POST {} returned 503: {}", url, body);
            }
            return body;
        } catch (IOException | ParseException e) {
            throw new LedgerClientException(LedgerClientException.IO_ERROR,
                    "POST " + url + " failed: " + e.getMessage(), e);
        }
    }

    int getStatusCode(String url) {
        HttpGet get = new HttpGet(url);
        try (CloseableHttpResponse response = httpClient.execute(get)) {
            return response.getCode();
        } catch (IOException e) {
            return -1;
        }
    }

    @Override
    public void close() {
        try {
            httpClient.close();
        } catch (IOException e) {
            log.debug("Error closing HTTP client: {}", e.getMessage());
        }
    }
}

package com.jio.rcs.operator.config;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * RestTemplate used exclusively by the Callback Engine to invoke the
 * CPaaS webhook. Timeouts are sourced from operator.callback.* so they can
 * be tuned without redeploying.
 *
 * <p><b>Backed by a pooled Apache HttpClient5, not the JDK default.</b> The
 * JDK's {@code SimpleClientHttpRequestFactory} that {@code RestTemplateBuilder}
 * falls back to has no configurable connection pool - it's governed by the
 * JVM-wide {@code http.maxConnections} property, which defaults to roughly 5
 * concurrent connections per destination host. That ceiling meant callback
 * delivery to a single webhook receiver could never exceed ~5 concurrent
 * in-flight HTTP requests no matter how many CALLBACK-queue dispatcher
 * threads were configured - once queue backpressure was fixed (see
 * InMemoryQueueService), this connection ceiling became the next bottleneck
 * standing between "queued for callback" and "genuinely delivered" at
 * high volume. A pooled connection manager with a much higher per-route and
 * total ceiling (operator.callback.max-connections-per-route /
 * max-total-connections) removes that bottleneck.
 */
@Configuration
@RequiredArgsConstructor
public class RestClientConfig {

    private final ProviderProperties providerProperties;

    @Bean
    public RestTemplate callbackRestTemplate() {
        var callback = providerProperties.getCallback();

        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(callback.getConnectTimeoutMillis()))
                .setSocketTimeout(Timeout.ofMilliseconds(callback.getReadTimeoutMillis()))
                .build();

        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(callback.getMaxTotalConnections())
                .setMaxConnPerRoute(callback.getMaxConnectionsPerRoute())
                .setDefaultConnectionConfig(connectionConfig)
                .build();

        RequestConfig requestConfig = RequestConfig.custom()
                .setResponseTimeout(Timeout.ofMilliseconds(callback.getReadTimeoutMillis()))
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                // A dead/idle pooled connection re-used after the receiver (or a
                // proxy in between) has silently closed it would otherwise surface
                // as a spurious IOException on an unlucky request; evicting
                // connections idle more than 30s costs nothing under real load and
                // eliminates that class of flaky callback failure.
                .evictIdleConnections(TimeValue.ofSeconds(30))
                .build();

        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        return new RestTemplate(factory);
    }
}

package io.kestra.plugin.elasticsearch.shared;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Regression coverage for the connect/response timeout override that used to be hardcoded (10s / 60s)
 * regardless of user configuration — a large bulk that previously took >60s to ack would silently start
 * failing. Both properties must now default to unset (native Rest5ClientBuilder behaviour: connect = 1s,
 * response = unbounded) and only be overridden when explicitly configured.
 */
@KestraTest
class ConnectionTimeoutRegressionTest {
    private static HttpServer server;
    private static String host;
    private static final AtomicLong RESPONSE_DELAY_MILLIS = new AtomicLong(0);

    @Inject
    private RunContextFactory runContextFactory;

    @BeforeAll
    static void beforeAll() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange ->
        {
            var delay = RESPONSE_DELAY_MILLIS.get();
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            var body = """
                {"_index":"timeout_regression","_id":"doc-1","_version":1,"result":"created","_shards":{"total":1,"successful":1,"failed":0},"_seq_no":0,"_primary_term":1}
                """;
            var payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "application/json");
            exchange.sendResponseHeaders(201, payload.length);
            try (var output = exchange.getResponseBody()) {
                output.write(payload);
            }
        });
        server.start();
        host = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void afterAll() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldNotImposeResponseTimeoutWhenUnset() throws Exception {
        RESPONSE_DELAY_MILLIS.set(1500);
        try {
            var runContext = runContextFactory.of();
            var connection = ElasticsearchConnection.builder()
                .hosts(List.of(host))
                .build();

            try (ElasticsearchClient client = connection.highLevelClient(runContext)) {
                var response = client.index(IndexRequest.of(builder -> builder
                    .index("timeout_regression")
                    .id("doc-1")
                    .document(Map.of("name", "john"))
                ));

                assertThat(response.id(), is("doc-1"));
            }
        } finally {
            RESPONSE_DELAY_MILLIS.set(0);
        }
    }

    @Test
    void shouldEnforceConfiguredResponseTimeout() throws Exception {
        RESPONSE_DELAY_MILLIS.set(1500);
        try {
            var runContext = runContextFactory.of();
            var connection = ElasticsearchConnection.builder()
                .hosts(List.of(host))
                .responseTimeout(Property.ofValue(Duration.ofMillis(200)))
                .build();

            try (ElasticsearchClient client = connection.highLevelClient(runContext)) {
                assertThrows(Exception.class, () -> client.index(IndexRequest.of(builder -> builder
                    .index("timeout_regression")
                    .id("doc-1")
                    .document(Map.of("name", "john"))
                )));
            }
        } finally {
            RESPONSE_DELAY_MILLIS.set(0);
        }
    }

    @Test
    void shouldApplyConfiguredConnectTimeoutWithoutBreakingNormalConnections() throws Exception {
        var runContext = runContextFactory.of();
        var connection = ElasticsearchConnection.builder()
            .hosts(List.of(host))
            .connectTimeout(Property.ofValue(Duration.ofSeconds(5)))
            .responseTimeout(Property.ofValue(Duration.ofSeconds(5)))
            .build();

        try (ElasticsearchClient client = connection.highLevelClient(runContext)) {
            var response = client.index(IndexRequest.of(builder -> builder
                .index("timeout_regression")
                .id("doc-1")
                .document(Map.of("name", "john"))
            ));

            assertThat(response.id(), is("doc-1"));
        }
    }
}

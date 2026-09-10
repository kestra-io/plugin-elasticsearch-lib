package io.kestra.plugin.elasticsearch.shared;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@KestraTest
class BasicAuthRegressionTest {
    private static final String USERNAME = "elastic";
    private static final String PASSWORD = "changeme";
    private static final String EXPECTED_AUTHORIZATION = "Basic " + Base64.getEncoder()
        .encodeToString((USERNAME + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
    private static final String EXPLICIT_AUTHORIZATION_HEADER = "Token XYZ";

    private static HttpServer server;
    private static String host;
    private static final AtomicReference<String> LAST_AUTHORIZATION_HEADER = new AtomicReference<>();
    private static final AtomicBoolean CHALLENGE_ONLY_MODE = new AtomicBoolean(false);
    private static final AtomicInteger REQUEST_COUNTER = new AtomicInteger();

    @Inject
    private RunContextFactory runContextFactory;

    @BeforeAll
    static void beforeAll() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange ->
        {
            var authorization = exchange.getRequestHeaders().getFirst("Authorization");
            LAST_AUTHORIZATION_HEADER.set(authorization);

            if (CHALLENGE_ONLY_MODE.get()) {
                var requestNumber = REQUEST_COUNTER.incrementAndGet();
                if (requestNumber == 1 && EXPECTED_AUTHORIZATION.equals(authorization)) {
                    writeUnauthorized(exchange);
                    return;
                }
            }

            var expectedAuthorization = EXPECTED_AUTHORIZATION.equals(authorization) || EXPLICIT_AUTHORIZATION_HEADER.equals(authorization);
            if (!expectedAuthorization) {
                writeUnauthorized(exchange);
                return;
            }

            var body = """
                {"_index":"auth_regression","_id":"doc-1","_version":1,"result":"created","_shards":{"total":1,"successful":1,"failed":0},"_seq_no":0,"_primary_term":1}
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

    private static void writeUnauthorized(HttpExchange exchange) throws IOException {
        var payload = "unauthorized".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "text/plain");
        exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"test\"");
        exchange.sendResponseHeaders(401, payload.length);
        try (var output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }

    @Test
    void shouldSendBasicAuthorizationHeaderWhenBasicAuthIsConfigured() throws Exception {
        var runContext = runContextFactory.of();
        var connection = ElasticsearchConnection.builder()
            .hosts(List.of(host))
            .basicAuth(
                ElasticsearchConnection.BasicAuth.builder()
                    .username(Property.ofValue(USERNAME))
                    .password(Property.ofValue(PASSWORD))
                    .build()
            )
            .build();

        try (ElasticsearchClient client = connection.highLevelClient(runContext)) {
            var response = client.index(IndexRequest.of(builder -> builder
                .index("auth_regression")
                .id("doc-1")
                .document(Map.of("name", "john"))
            ));

            assertThat(response.id(), is("doc-1"));
        }
        assertThat(LAST_AUTHORIZATION_HEADER.get(), is(EXPECTED_AUTHORIZATION));
    }

    @Test
    void shouldPreferExplicitAuthorizationHeaderOverBasicAuth() throws Exception {
        var runContext = runContextFactory.of();
        var connection = ElasticsearchConnection.builder()
            .hosts(List.of(host))
            .basicAuth(
                ElasticsearchConnection.BasicAuth.builder()
                    .username(Property.ofValue(USERNAME))
                    .password(Property.ofValue("wrong-password"))
                    .build()
            )
            .headers(Property.ofValue(List.of("Authorization: " + EXPLICIT_AUTHORIZATION_HEADER)))
            .build();

        try (ElasticsearchClient client = connection.highLevelClient(runContext)) {
            var response = client.index(IndexRequest.of(builder -> builder
                .index("auth_regression")
                .id("doc-1")
                .document(Map.of("name", "john"))
            ));

            assertThat(response.id(), is("doc-1"));
        }
        assertThat(LAST_AUTHORIZATION_HEADER.get(), is(EXPLICIT_AUTHORIZATION_HEADER));
    }

    @Test
    void shouldHandleBasicAuthChallengeFlowWhenPreemptiveAuthRejected() throws Exception {
        CHALLENGE_ONLY_MODE.set(true);
        REQUEST_COUNTER.set(0);
        try {
            var runContext = runContextFactory.of();
            var connection = ElasticsearchConnection.builder()
                .hosts(List.of(host))
                .basicAuth(
                    ElasticsearchConnection.BasicAuth.builder()
                        .username(Property.ofValue(USERNAME))
                        .password(Property.ofValue(PASSWORD))
                        .build()
                )
                .build();

            try (ElasticsearchClient client = connection.highLevelClient(runContext)) {
                var response = client.index(IndexRequest.of(builder -> builder
                    .index("auth_regression")
                    .id("doc-1")
                    .document(Map.of("name", "john"))
                ));

                assertThat(response.id(), is("doc-1"));
            }
        } finally {
            CHALLENGE_ONLY_MODE.set(false);
        }
    }
}

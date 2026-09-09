package io.kestra.plugin.elasticsearch.shared;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class TargetServerVersionRegressionTest {
    private static final ElasticsearchContainer ELASTICSEARCH_8_CONTAINER;

    static {
        ELASTICSEARCH_8_CONTAINER = new ElasticsearchContainer(DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.17.2"))
            .withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false");
        ELASTICSEARCH_8_CONTAINER.start();
    }

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() ->
        {
            if (ELASTICSEARCH_8_CONTAINER != null) {
                ELASTICSEARCH_8_CONTAINER.stop();
            }
        }));
    }

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void shouldFailAgainstElasticsearch8WhenTargetServerVersionIs9() throws Exception {
        var runContext = runContextFactory.of();
        var index = "ut_" + IdUtils.create().toLowerCase(Locale.ROOT);
        var connection = ElasticsearchConnection.builder()
            .hosts(List.of("http://" + ELASTICSEARCH_8_CONTAINER.getHttpHostAddress()))
            .targetServerVersion(Property.ofValue(9))
            .build();

        try (ElasticsearchClient client = connection.highLevelClient(runContext)) {
            var exception = assertThrows(Exception.class, () -> client.index(IndexRequest.of(builder -> builder
                .index(index)
                .document(Map.of("name", "john"))
            )));

            var allMessages = allExceptionMessages(exception);
            assertThat(allMessages, containsString("media_type_header_exception"));
            assertThat(allMessages, containsString("Invalid media-type value on headers"));
        }
    }

    private String allExceptionMessages(Throwable throwable) {
        var message = new StringBuilder();
        var current = throwable;

        while (current != null) {
            if (current.getMessage() != null) {
                message.append(current.getMessage()).append('\n');
            }
            current = current.getCause();
        }

        return message.toString();
    }

    @Test
    void shouldUseElasticsearch8CompatibleHeaderByDefault() throws Exception {
        var runContext = runContextFactory.of();
        var index = "ut_" + IdUtils.create().toLowerCase(Locale.ROOT);
        var connection = ElasticsearchConnection.builder()
            .hosts(List.of("http://" + ELASTICSEARCH_8_CONTAINER.getHttpHostAddress()))
            .build();

        try (ElasticsearchClient client = connection.highLevelClient(runContext)) {
            var response = client.index(IndexRequest.of(builder -> builder
                .index(index)
                .document(Map.of("name", "john"))
            ));

            assertThat(response.id(), notNullValue());
        }
    }
}

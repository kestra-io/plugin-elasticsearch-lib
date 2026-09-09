package io.kestra.plugin.elasticsearch.shared;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.runners.RunContextFactory;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.bulk.IndexOperation;
import co.elastic.clients.elasticsearch.core.bulk.OperationType;
import jakarta.inject.Inject;
import reactor.core.publisher.Flux;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@KestraTest
class BulkServiceTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void shouldRecordMetricsAndReturnCountOnSuccessfulBulk() throws Exception {
        var runContext = runContextFactory.of();
        var client = mock(ElasticsearchClient.class);
        when(client.bulk(any(BulkRequest.class))).thenReturn(successfulResponse());

        var operations = Flux.fromIterable(List.of(
            indexOperation("1"),
            indexOperation("2"),
            indexOperation("3")
        ));

        var count = BulkService.executeBulk(runContext, client, operations, 10);

        assertThat(count.get(), is(3L));
        assertThat(runContext.metrics().stream().filter(e -> e.getName().equals("requests.count")).findFirst().orElseThrow().getValue(), is(1D));
        assertThat(runContext.metrics().stream().filter(e -> e.getName().equals("records")).findFirst().orElseThrow().getValue(), is(3D));
        assertThat(runContext.metrics().stream().filter(e -> e.getName().equals("requests.duration")).findFirst().orElseThrow().getValue(), is(Duration.ofNanos(42)));
    }

    @Test
    void shouldThrowWithAggregatedErrorsWhenBulkResponseHasErrors() throws Exception {
        var runContext = runContextFactory.of();
        var client = mock(ElasticsearchClient.class);
        when(client.bulk(any(BulkRequest.class))).thenReturn(failedResponse());

        var operations = Flux.just(indexOperation("1"));

        var exception = assertThrows(RuntimeException.class, () -> BulkService.executeBulk(runContext, client, operations, 10));

        assertThat(exception.getMessage(), containsString("Indexer failed bulk"));
        assertThat(exception.getMessage(), containsString("ut_index"));
        assertThat(exception.getMessage(), containsString("document already exists"));
    }

    private BulkOperation indexOperation(String id) {
        return BulkOperation.of(builder -> builder
            .index(IndexOperation.of((IndexOperation.Builder<Object> indexBuilder) -> indexBuilder
                .index("ut_index")
                .id(id)
                .document(Map.of("field", "value"))
            ))
        );
    }

    private BulkResponse successfulResponse() {
        return BulkResponse.of(builder -> builder
            .errors(false)
            .took(42)
            .items(List.of(
                bulkResponseItem("1", null),
                bulkResponseItem("2", null),
                bulkResponseItem("3", null)
            ))
        );
    }

    private BulkResponse failedResponse() {
        return BulkResponse.of(builder -> builder
            .errors(true)
            .took(1)
            .items(List.of(bulkResponseItem("1", "document already exists")))
        );
    }

    private BulkResponseItem bulkResponseItem(String id, String errorReason) {
        return BulkResponseItem.of(builder ->
        {
            builder
                .operationType(OperationType.Index)
                .index("ut_index")
                .id(id)
                .status(errorReason == null ? 201 : 409);

            if (errorReason != null) {
                builder.error(errorBuilder -> errorBuilder.reason(errorReason));
            }

            return builder;
        });
    }
}

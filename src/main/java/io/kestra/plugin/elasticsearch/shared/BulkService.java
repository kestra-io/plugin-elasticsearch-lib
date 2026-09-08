package io.kestra.plugin.elasticsearch.shared;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;

import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.executions.metrics.Timer;
import io.kestra.core.runners.RunContext;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import reactor.core.publisher.Flux;

import static io.kestra.core.utils.Rethrow.throwFunction;

/**
 * Shared bulk-indexing logic, consumed by both {@code plugin-elasticsearch} (OSS, via {@code AbstractLoad})
 * and {@code plugin-ee-elasticsearch} (EE, via {@code LogExporter}).
 */
public class BulkService {
    private BulkService() {
    }

    public static AtomicLong executeBulk(
        RunContext runContext,
        ElasticsearchClient client,
        Flux<BulkOperation> operationFlux,
        Integer bufferSize) throws IOException {
        AtomicLong count = new AtomicLong();
        AtomicLong duration = new AtomicLong();
        Logger logger = runContext.logger();

        Flux<BulkResponse> flowable = operationFlux
            .doOnNext(docWriteRequest ->
            {
                count.incrementAndGet();
            })
            .buffer(bufferSize, bufferSize)
            .map(throwFunction(indexRequests ->
            {
                var bulkRequest = new BulkRequest.Builder();
                bulkRequest.operations(indexRequests);

                return client.bulk(bulkRequest.build());
            }))
            .doOnNext(bulkItemResponse ->
            {
                duration.addAndGet(bulkItemResponse.took());

                if (bulkItemResponse.errors()) {
                    throw new RuntimeException("Indexer failed bulk:\n " + logError(bulkItemResponse));
                }
            });

        // metrics & finalize
        Long requestCount = flowable.count().blockOptional().orElse(0L);
        runContext.metric(Counter.of("requests.count", requestCount));
        runContext.metric(Counter.of("records", count.get()));
        runContext.metric(Timer.of("requests.duration", Duration.ofNanos(duration.get())));

        logger.info(
            "Successfully send {} requests for {} records in {}",
            requestCount,
            count.get(),
            Duration.ofNanos(duration.get())
        );
        return count;
    }

    private static String logError(BulkResponse bulkResponse) {
        StringBuilder builder = new StringBuilder();
        bulkResponse.items().forEach(
            responseItem ->
            {
                if (responseItem.error() != null) {
                    builder
                        .append(responseItem.index()).append(": ")
                        .append(responseItem.status()).append(" - ")
                        .append(responseItem.error().reason()).append('\n');
                }
            }
        );
        return builder.toString();
    }
}

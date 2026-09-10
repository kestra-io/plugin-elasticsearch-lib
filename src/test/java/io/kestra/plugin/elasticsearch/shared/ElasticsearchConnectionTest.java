package io.kestra.plugin.elasticsearch.shared;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class ElasticsearchConnectionTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void shouldBuildCompatibleMediaTypeForTargetServerVersion8() throws Exception {
        var runContext = runContextFactory.of();
        var connection = ElasticsearchConnection.builder()
            .hosts(List.of("http://localhost:9200"))
            .targetServerVersion(Property.ofValue(8))
            .build();

        assertThat(connection.compatibleMediaType(runContext), is("application/vnd.elasticsearch+json; compatible-with=8"));
    }

    @Test
    void shouldBuildCompatibleMediaTypeForTargetServerVersion9() throws Exception {
        var runContext = runContextFactory.of();
        var connection = ElasticsearchConnection.builder()
            .hosts(List.of("http://localhost:9200"))
            .targetServerVersion(Property.ofValue(9))
            .build();

        assertThat(connection.compatibleMediaType(runContext), is("application/vnd.elasticsearch+json; compatible-with=9"));
    }

    @Test
    void shouldRejectTargetServerVersionBelowLowerBound() {
        var runContext = runContextFactory.of();
        var connection = ElasticsearchConnection.builder()
            .hosts(List.of("http://localhost:9200"))
            .targetServerVersion(Property.ofValue(7))
            .build();

        var exception = assertThrows(IllegalArgumentException.class, () -> connection.compatibleMediaType(runContext));
        assertThat(exception.getMessage(), is("`targetServerVersion` must be 8 or 9"));
    }

    @Test
    void shouldRejectTargetServerVersionAboveUpperBound() {
        var runContext = runContextFactory.of();
        var connection = ElasticsearchConnection.builder()
            .hosts(List.of("http://localhost:9200"))
            .targetServerVersion(Property.ofValue(10))
            .build();

        var exception = assertThrows(IllegalArgumentException.class, () -> connection.compatibleMediaType(runContext));
        assertThat(exception.getMessage(), is("`targetServerVersion` must be 8 or 9"));
    }

    @Test
    void shouldRejectMalformedHeader() {
        var runContext = runContextFactory.of();
        var connection = ElasticsearchConnection.builder()
            .hosts(List.of("http://localhost:9200"))
            .headers(Property.ofValue(List.of("NoColonHere")))
            .build();

        var exception = assertThrows(IllegalArgumentException.class, () -> connection.client(runContext));
        assertThat(exception.getMessage(), containsString("Invalid header format, expected `Name: Value` but got `NoColonHere`"));
    }

    @Test
    void shouldRejectHostWithoutScheme() {
        var runContext = runContextFactory.of();
        var connection = ElasticsearchConnection.builder()
            .hosts(List.of("localhost:9200"))
            .build();

        var exception = assertThrows(IllegalArgumentException.class, () -> connection.client(runContext));
        assertThat(exception.getMessage(), containsString("expected a URI with a scheme"));
    }
}

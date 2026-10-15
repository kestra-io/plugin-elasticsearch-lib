package io.kestra.plugin.elasticsearch.shared;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.nio.AsyncEntityProducer;
import org.apache.hc.core5.http.nio.DataStreamChannel;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NullSafeEntityProducerTest {
    @Test
    void shouldReportNothingAvailableOnceBodyIsFullySent() throws Exception {
        var entity = multiBufferEntity("{\"index\":{}}\n", "{\"a\":1}\n");
        var producer = new NullSafeEntityProducer(entity);

        assertThat(producer.available(), is(greaterThan(0)));

        var channel = new DrainingChannel();
        while (!channel.ended) {
            producer.produce(channel);
        }

        assertThrows(NullPointerException.class, entity::available);
        assertThat(producer.available(), is(0));

        producer.releaseResources();
        assertThat(producer.available(), is(greaterThan(0)));
    }

    // MultiBufferEntity is package-private: the entity elasticsearch-java's Rest5 transport sends for every request body
    private static AsyncEntityProducer multiBufferEntity(String... chunks) throws Exception {
        var constructor = Class.forName("co.elastic.clients.transport.rest5_client.MultiBufferEntity")
            .getDeclaredConstructor(Iterable.class, ContentType.class);
        constructor.setAccessible(true);
        var buffers = Arrays.stream(chunks)
            .map(chunk -> ByteBuffer.wrap(chunk.getBytes(StandardCharsets.UTF_8)))
            .toList();
        return (AsyncEntityProducer) constructor.newInstance(buffers, ContentType.APPLICATION_JSON);
    }

    private static class DrainingChannel implements DataStreamChannel {
        private boolean ended;

        @Override
        public void requestOutput() {
        }

        @Override
        public int write(ByteBuffer src) {
            var written = src.remaining();
            src.position(src.limit());
            return written;
        }

        @Override
        public void endStream(List<? extends Header> trailers) {
            ended = true;
        }

        @Override
        public void endStream() {
            ended = true;
        }
    }
}

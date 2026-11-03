package io.kestra.plugin.elasticsearch.shared;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.nio.AsyncEntityProducer;
import org.apache.hc.core5.http.nio.DataStreamChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BufferedEntityProducerTest {
    private static final String BODY = "{\"index\":{}}\n{\"a\":1}\n";

    @Test
    void shouldReplayFullBodyAfterReleaseWhenEntityIsBuffered() throws Exception {
        var entity = multiBufferEntity("{\"index\":{}}\n", "{\"a\":1}\n");

        var buffered = BufferedEntityProducer.of(entity);

        assertThat(drain(buffered), is(BODY));
        buffered.releaseResources();
        assertThat(drain(buffered), is(BODY));
        assertThat(buffered.getContentType(), is(ContentType.APPLICATION_JSON.toString()));
    }

    @Test
    void shouldFailInsteadOfProducingEmptyBodyWhenEntityWasProducedWithoutReset() throws Exception {
        var entity = multiBufferEntity("{\"index\":{}}\n", "{\"a\":1}\n");
        drain(entity);

        var exception = assertThrows(IllegalStateException.class, () -> BufferedEntityProducer.of(entity));

        assertThat(exception.getMessage(), containsString("empty"));
    }

    @Test
    @Timeout(5)
    void shouldFailWhenProducerNeverMakesProgress() {
        var stalled = mock(AsyncEntityProducer.class);
        when(stalled.getContentEncoding()).thenReturn(null);

        var exception = assertThrows(IllegalStateException.class, () -> BufferedEntityProducer.of(stalled));

        assertThat(exception.getMessage(), containsString("no progress"));
    }

    @Test
    void shouldReturnSameProducerWhenContentIsEncoded() throws Exception {
        var encoded = mock(AsyncEntityProducer.class);
        when(encoded.getContentEncoding()).thenReturn("gzip");

        assertThat(BufferedEntityProducer.of(encoded), is(encoded));
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

    private static String drain(AsyncEntityProducer producer) throws Exception {
        var channel = new DrainingChannel();
        while (!channel.ended) {
            producer.produce(channel);
        }
        return channel.body.toString(StandardCharsets.UTF_8);
    }

    private static class DrainingChannel implements DataStreamChannel {
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private boolean ended;

        @Override
        public void requestOutput() {
        }

        @Override
        public int write(ByteBuffer src) {
            var written = src.remaining();
            var chunk = new byte[written];
            src.get(chunk);
            body.write(chunk, 0, written);
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

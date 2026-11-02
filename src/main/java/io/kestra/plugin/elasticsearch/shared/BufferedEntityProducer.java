package io.kestra.plugin.elasticsearch.shared;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.nio.AsyncEntityProducer;
import org.apache.hc.core5.http.nio.DataStreamChannel;
import org.apache.hc.core5.http.nio.entity.BasicAsyncEntityProducer;

/**
 * Replaces the request body producer with an immutable in-memory copy, so the I/O reactor never polls
 * elasticsearch-java's {@code MultiBufferEntity}, whose mutable cursor can yield an empty body.
 */
final class BufferedEntityProducer {
    private static final int MAX_IDLE_PRODUCE_CALLS = 100;

    private BufferedEntityProducer() {
    }

    /**
     * Reads the whole body through {@code produce()} and releases the original producer.
     * Content-encoded producers are returned unchanged.
     *
     * @throws IllegalStateException if the body is empty or the producer stops making progress
     */
    static AsyncEntityProducer of(AsyncEntityProducer original) throws IOException {
        if (original.getContentEncoding() != null) {
            return original;
        }

        var channel = new CollectingChannel();
        var idleCalls = 0;
        while (!channel.ended) {
            var before = channel.body.size();
            original.produce(channel);
            idleCalls = channel.body.size() == before ? idleCalls + 1 : 0;
            if (idleCalls > MAX_IDLE_PRODUCE_CALLS) {
                throw new IllegalStateException(
                    "The request body producer made no progress after %d consecutive attempts, so the request was not sent.".formatted(MAX_IDLE_PRODUCE_CALLS)
                );
            }
        }
        original.releaseResources();

        if (channel.body.size() == 0) {
            throw new IllegalStateException("The request body was empty, so the request was not sent.");
        }

        var contentType = original.getContentType() == null ? ContentType.APPLICATION_JSON : ContentType.parse(original.getContentType());
        return new BasicAsyncEntityProducer(channel.body.toByteArray(), contentType);
    }

    private static final class CollectingChannel implements DataStreamChannel {
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

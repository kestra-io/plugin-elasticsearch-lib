package io.kestra.plugin.elasticsearch.shared;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
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

    static AsyncEntityProducer of(AsyncEntityProducer original) throws IOException {
        if (original.getContentEncoding() != null) {
            return original;
        }

        var channel = new CollectingChannel();
        var idleCalls = 0;
        while (!channel.ended) {
            var before = channel.size;
            original.produce(channel);
            idleCalls = channel.size == before ? idleCalls + 1 : 0;
            if (idleCalls >= MAX_IDLE_PRODUCE_CALLS) {
                throw new IllegalStateException(
                    "The request body producer made no progress after %d consecutive attempts, so the request was not sent. Retry the task, and report it to the plugin maintainers if it keeps happening.".formatted(MAX_IDLE_PRODUCE_CALLS)
                );
            }
        }
        original.releaseResources();

        if (channel.size == 0) {
            throw new IllegalStateException("The request body was empty, so the request was not sent. Retry the task, and report it to the plugin maintainers if it keeps happening.");
        }

        var contentType = original.getContentType() == null ? ContentType.APPLICATION_JSON : ContentType.parse(original.getContentType());
        return new BasicAsyncEntityProducer(channel.toByteArray(), contentType);
    }

    private static final class CollectingChannel implements DataStreamChannel {
        private final List<ByteBuffer> chunks = new ArrayList<>();
        private int size;
        private boolean ended;

        @Override
        public void requestOutput() {
        }

        @Override
        public int write(ByteBuffer src) {
            var written = src.remaining();
            chunks.add(ByteBuffer.allocate(written).put(src).flip());
            size += written;
            return written;
        }

        private byte[] toByteArray() {
            var body = new byte[size];
            var offset = 0;
            for (var chunk : chunks) {
                var length = chunk.remaining();
                chunk.get(body, offset, length);
                offset += length;
            }
            return body;
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

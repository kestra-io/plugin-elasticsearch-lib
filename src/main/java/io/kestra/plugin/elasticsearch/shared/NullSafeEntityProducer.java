package io.kestra.plugin.elasticsearch.shared;

import org.apache.hc.core5.http.nio.AsyncEntityProducer;
import org.apache.hc.core5.http.nio.entity.AsyncEntityProducerWrapper;

/**
 * Works around elasticsearch-java's {@code rest5_client.MultiBufferEntity#available()}, which dereferences
 * {@code currentBuffer} without a null check although {@code produce()} nulls it once the last buffer is sent.
 * An NPE there means the body is fully sent, so nothing is left to write.
 */
class NullSafeEntityProducer extends AsyncEntityProducerWrapper {
    NullSafeEntityProducer(AsyncEntityProducer wrappedEntityProducer) {
        super(wrappedEntityProducer);
    }

    @Override
    public int available() {
        try {
            return super.available();
        } catch (NullPointerException e) {
            return 0;
        }
    }
}

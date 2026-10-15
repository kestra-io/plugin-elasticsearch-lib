package io.kestra.plugin.elasticsearch.shared;

import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.hc.core5.http.nio.AsyncEntityProducer;
import org.apache.hc.core5.http.nio.entity.AsyncEntityProducerWrapper;
import org.slf4j.Logger;

/**
 * Works around elasticsearch-java's {@code rest5_client.MultiBufferEntity#available()}, which dereferences
 * {@code currentBuffer} without a null check although {@code produce()} nulls it once the last buffer is sent.
 * An NPE there means the body is fully sent, so nothing is left to write.
 */
class NullSafeEntityProducer extends AsyncEntityProducerWrapper {
    private final Logger logger;
    // available() is polled repeatedly by the I/O reactor: log once per request
    private final AtomicBoolean logged = new AtomicBoolean();

    NullSafeEntityProducer(AsyncEntityProducer wrappedEntityProducer, Logger logger) {
        super(wrappedEntityProducer);
        this.logger = logger;
    }

    @Override
    public int available() {
        try {
            return super.available();
        } catch (NullPointerException e) {
            if (logged.compareAndSet(false, true)) {
                logger.warn("Ignored NullPointerException from the Elasticsearch client request entity once the request body was fully sent (known elasticsearch-java MultiBufferEntity defect)", e);
            }
            return 0;
        }
    }
}

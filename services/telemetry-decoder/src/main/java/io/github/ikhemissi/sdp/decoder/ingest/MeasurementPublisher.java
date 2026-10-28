package io.github.ikhemissi.sdp.decoder.ingest;

import java.util.List;

/**
 * Publishes persisted measurements for other consumers (alerting, ADR-0009). Called after the batch is stored and
 * before it is acknowledged; a failure must throw so the batch is retried and never acknowledged unpublished.
 */
@FunctionalInterface
public interface MeasurementPublisher {

    /** Publication disabled ({@code ALERTING_PUBLISH_ENABLED=false}). */
    MeasurementPublisher NONE = measurements -> {
    };

    void publish(List<DecodedBatch.Measurement> measurements);
}

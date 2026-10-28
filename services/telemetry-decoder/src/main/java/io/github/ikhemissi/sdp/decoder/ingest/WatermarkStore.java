package io.github.ikhemissi.sdp.decoder.ingest;

import java.time.Instant;

/**
 * Ingestion watermarks per Event Hubs partition (M3 design D2): every message enqueued before a partition's watermark
 * has been persisted. The device API caches history chunks as immutable only when they end before the lowest
 * watermark, so a decoder backlog never freezes incomplete data in the cache.
 */
public interface WatermarkStore {

    /** Registers a partition the decoder starts processing (at -infinity if unknown, so it holds the minimum back). */
    void register(String partitionId);

    /** Moves the partition's watermark forward (never backwards). */
    void advance(String partitionId, Instant watermark);
}

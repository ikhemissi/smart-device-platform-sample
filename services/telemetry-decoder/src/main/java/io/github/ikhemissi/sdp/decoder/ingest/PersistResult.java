package io.github.ikhemissi.sdp.decoder.ingest;

/**
 * @param storedMeasurements measurements newly stored
 * @param storedRejections   rejected messages newly stored
 * @param duplicates         messages already stored by a previous delivery (ignored)
 */
public record PersistResult(int storedMeasurements, int storedRejections, int duplicates) {
}

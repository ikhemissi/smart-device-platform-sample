package io.github.ikhemissi.sdp.decoder.ingest;

import java.util.Set;

/** Stores a decoded batch atomically and idempotently. */
public interface TelemetryStore {

    /**
     * Persists measurements and rejections whose (device, message key) was never stored before, registers or
     * updates devices, and creates unknown sites — all in one transaction.
     *
     * @return what was stored and the keys ({@code deviceId + '\n' + messageKey}) recognised as duplicates
     */
    StoreResult persist(DecodedBatch batch);

    record StoreResult(PersistResult counts, Set<String> duplicateKeys) {
    }

    static String key(String deviceId, String messageKey) {
        return (deviceId == null ? "" : deviceId) + '\n' + messageKey;
    }
}

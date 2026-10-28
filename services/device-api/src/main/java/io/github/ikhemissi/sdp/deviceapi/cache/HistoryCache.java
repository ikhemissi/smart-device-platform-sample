package io.github.ikhemissi.sdp.deviceapi.cache;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Cache-aside store for history results. Every failure is a miss: callers always fall back to the database. */
public interface HistoryCache {

    /** Values of the given keys, {@code null} for misses (same order as {@code keys}). */
    List<String> getAll(List<String> keys);

    /** Stores values with their time to live; failures are ignored. */
    void putAll(Map<String, Entry> entries);

    String key(String suffix);

    record Entry(String value, Duration ttl) {
    }

    /** No cache configured: every lookup misses. */
    HistoryCache NONE = new HistoryCache() {
        @Override
        public List<String> getAll(List<String> keys) {
            return java.util.Arrays.asList(new String[keys.size()]);
        }

        @Override
        public void putAll(Map<String, Entry> entries) {
        }

        @Override
        public String key(String suffix) {
            return suffix;
        }
    };
}

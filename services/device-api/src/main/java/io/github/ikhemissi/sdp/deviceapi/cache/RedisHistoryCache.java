package io.github.ikhemissi.sdp.deviceapi.cache;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SetArgs;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Azure Managed Redis behind a circuit breaker (M3 design D2): commands time out after {@code commandTimeout}; after
 * {@code failureThreshold} consecutive failures the cache is bypassed for {@code openDuration}. Failures are counted in
 * {@code sdp.cache.errors} and never reach the caller. Connects in the background (the API starts and serves without
 * Redis); requests never wait for a connection.
 */
public class RedisHistoryCache implements HistoryCache, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisHistoryCache.class);
    private static final AttributeKey<String> OPERATION = AttributeKey.stringKey("sdp.cache.operation");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final CacheProperties properties;
    private final RedisClient client;
    private final RedisURI uri;
    private final Clock clock;
    private final LongCounter errors;
    private final LongCounter lookups;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile Instant openUntil = Instant.MIN;
    private volatile StatefulRedisConnection<String, String> connection;
    private final java.util.concurrent.atomic.AtomicBoolean connecting = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile Instant nextConnectAttempt = Instant.MIN;

    public RedisHistoryCache(CacheProperties properties, EntraIdRedisCredentialsProvider credentials,
                             OpenTelemetry openTelemetry, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.uri = RedisURI.builder()
                .withHost(properties.host())
                .withPort(properties.port())
                .withSsl(true)
                .withAuthentication(credentials)
                // Connection initialisation (TLS, HELLO with the Entra token); commands use commandTimeout.
                .withTimeout(CONNECT_TIMEOUT)
                .build();
        this.client = RedisClient.create();
        this.client.setOptions(ClientOptions.builder()
                .reauthenticateBehavior(ClientOptions.ReauthenticateBehavior.ON_NEW_CREDENTIALS)
                .timeoutOptions(TimeoutOptions.enabled(properties.commandTimeout()))
                .socketOptions(SocketOptions.builder().connectTimeout(CONNECT_TIMEOUT).build())
                // Never queue commands while disconnected: a missing cache must cost nothing.
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build());
        var meter = openTelemetry.getMeter("sdp.device-api");
        this.errors = meter.counterBuilder("sdp.cache.errors")
                .setDescription("History cache operations that failed or were skipped by the circuit breaker").build();
        this.lookups = meter.counterBuilder("sdp.cache.lookups")
                .setDescription("History cache keys looked up, by outcome (hit or miss)").build();
    }

    @Override
    public String key(String suffix) {
        return properties.keyPrefix() + suffix;
    }

    @Override
    public List<String> getAll(List<String> keys) {
        List<String> misses = Arrays.asList(new String[keys.size()]);
        if (keys.isEmpty()) {
            return misses;
        }
        RedisAsyncCommands<String, String> redis = commands("get");
        if (redis == null) {
            count(0, keys.size());
            return misses;
        }
        try {
            List<KeyValue<String, String>> values = redis.mget(keys.toArray(String[]::new))
                    .get(properties.commandTimeout().toMillis(), TimeUnit.MILLISECONDS);
            List<String> result = new ArrayList<>(keys.size());
            int hits = 0;
            for (KeyValue<String, String> value : values) {
                String v = value.getValueOrElse(null);
                result.add(v);
                hits += v == null ? 0 : 1;
            }
            succeeded();
            count(hits, keys.size() - hits);
            return result;
        } catch (Exception e) {
            failed("get", e);
            count(0, keys.size());
            return misses;
        }
    }

    @Override
    public void putAll(Map<String, Entry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        RedisAsyncCommands<String, String> redis = commands("put");
        if (redis == null) {
            return;
        }
        try {
            var futures = entries.entrySet().stream()
                    .map(e -> redis.set(e.getKey(), e.getValue().value(), SetArgs.Builder.px(e.getValue().ttl())).toCompletableFuture())
                    .toArray(java.util.concurrent.CompletableFuture[]::new);
            java.util.concurrent.CompletableFuture.allOf(futures).get(properties.commandTimeout().toMillis() * 2, TimeUnit.MILLISECONDS);
            succeeded();
        } catch (Exception e) {
            failed("put", e);
        }
    }

    /** PING, used to verify Entra authentication at startup (waits for the connection). */
    public String ping() throws Exception {
        connectInBackground();
        for (int i = 0; i < 50 && connection == null; i++) {
            Thread.sleep(200);
        }
        StatefulRedisConnection<String, String> current = connection;
        if (current == null) {
            throw new IllegalStateException("Redis connection not established");
        }
        return current.async().ping().get(5, TimeUnit.SECONDS);
    }

    /** Starts connecting if not connected (at most once per open duration after a failure). */
    public void connectInBackground() {
        if (connection != null || clock.instant().isBefore(nextConnectAttempt) || !connecting.compareAndSet(false, true)) {
            return;
        }
        client.connectAsync(io.lettuce.core.codec.StringCodec.UTF8, uri).whenComplete((connected, error) -> {
            if (error == null) {
                connection = connected;
                log.info("History cache connection established ({}:{})", properties.host(), properties.port());
            } else {
                nextConnectAttempt = clock.instant().plus(properties.openDuration());
                errors.add(1, Attributes.of(OPERATION, "connect"));
                log.warn("History cache connection failed, retrying in {}: {}", properties.openDuration(), rootCause(error));
            }
            connecting.set(false);
        });
    }

    private RedisAsyncCommands<String, String> commands(String operation) {
        if (clock.instant().isBefore(openUntil)) {
            errors.add(1, Attributes.of(OPERATION, operation + "-bypassed"));
            return null;
        }
        StatefulRedisConnection<String, String> current = connection;
        if (current == null) {
            connectInBackground();
            return null;
        }
        return current.async();
    }

    private static String rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.toString();
    }

    private void succeeded() {
        consecutiveFailures.set(0);
    }

    private void failed(String operation, Exception e) {
        errors.add(1, Attributes.of(OPERATION, operation));
        if (consecutiveFailures.incrementAndGet() >= properties.failureThreshold()) {
            openUntil = clock.instant().plus(properties.openDuration());
            consecutiveFailures.set(0);
            log.warn("History cache bypassed for {} after repeated failures (last: {})", properties.openDuration(), e.toString());
        } else {
            log.debug("History cache {} failed", operation, e);
        }
    }

    private void count(int hits, int misses) {
        if (hits > 0) {
            lookups.add(hits, Attributes.of(AttributeKey.stringKey("sdp.cache.outcome"), "hit"));
        }
        if (misses > 0) {
            lookups.add(misses, Attributes.of(AttributeKey.stringKey("sdp.cache.outcome"), "miss"));
        }
    }

    @Override
    public void close() {
        if (connection != null) {
            connection.close();
        }
        client.shutdown();
    }
}

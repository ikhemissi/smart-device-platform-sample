package io.github.ikhemissi.sdp.deviceapi.cache;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import io.lettuce.core.RedisCredentials;
import io.lettuce.core.RedisCredentialsProvider;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

/**
 * Microsoft Entra ID credentials for Azure Managed Redis (ADR-0005, M3 design D2): an access token for
 * {@value #SCOPE} as password and the identity's object id as user name. Tokens come from the platform's
 * {@link TokenCredential} (managed identity in Azure, developer identity locally), are renewed before they expire and
 * streamed to Lettuce, which re-authenticates open connections.
 */
public final class EntraIdRedisCredentialsProvider implements RedisCredentialsProvider {

    public static final String SCOPE = "https://redis.azure.com/.default";
    private static final Logger log = LoggerFactory.getLogger(EntraIdRedisCredentialsProvider.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final TokenCredential credential;
    private final Duration refreshBefore;
    private final Clock clock;
    private final Sinks.Many<RedisCredentials> updates = Sinks.many().replay().latest();
    private volatile RedisCredentials current;
    private volatile Instant expiresAt = Instant.MIN;

    public EntraIdRedisCredentialsProvider(TokenCredential credential, Duration refreshBefore, Clock clock) {
        this.credential = credential;
        this.refreshBefore = refreshBefore;
        this.clock = clock;
    }

    @Override
    public Mono<RedisCredentials> resolveCredentials() {
        return Mono.fromCallable(this::currentOrRefreshed).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public Flux<RedisCredentials> credentials() {
        return updates.asFlux();
    }

    /** Renews the token when it is close to expiry; called periodically so connections re-authenticate in time. */
    public void refreshIfNeeded() {
        if (needsRefresh()) {
            try {
                refresh();
            } catch (RuntimeException e) {
                log.warn("Could not renew the Redis access token: {}", e.toString());
            }
        }
    }

    private RedisCredentials currentOrRefreshed() {
        RedisCredentials credentials = current;
        return credentials != null && !needsRefresh() ? credentials : refresh();
    }

    private boolean needsRefresh() {
        return !clock.instant().isBefore(expiresAt.minus(refreshBefore));
    }

    private synchronized RedisCredentials refresh() {
        if (current != null && !needsRefresh()) {
            return current;
        }
        AccessToken token = credential.getTokenSync(new TokenRequestContext().addScopes(SCOPE));
        RedisCredentials credentials = RedisCredentials.just(objectId(token.getToken()), token.getToken());
        current = credentials;
        expiresAt = token.getExpiresAt().toInstant();
        updates.tryEmitNext(credentials);
        log.debug("Redis access token renewed for {}, valid until {}", credentials.getUsername(), expiresAt);
        return credentials;
    }

    /** Object id ({@code oid} claim) of the identity the token was issued to: the Redis user name. */
    static String objectId(String jwt) {
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Not a JWT access token");
        }
        byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
        String oid = JSON.readTree(new String(payload, StandardCharsets.UTF_8)).path("oid").asString(null);
        if (oid == null || oid.isBlank()) {
            throw new IllegalArgumentException("Access token has no oid claim");
        }
        return oid;
    }
}

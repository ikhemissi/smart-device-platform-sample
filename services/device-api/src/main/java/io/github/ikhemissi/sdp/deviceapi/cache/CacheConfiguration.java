package io.github.ikhemissi.sdp.deviceapi.cache;

import com.azure.core.credential.TokenCredential;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class CacheConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CacheConfiguration.class);

    @Bean
    EntraIdRedisCredentialsProvider redisCredentials(ObjectProvider<TokenCredential> credential, Clock clock) {
        return new EntraIdRedisCredentialsProvider(credential.getIfAvailable(() -> new com.azure.identity.DefaultAzureCredentialBuilder().build()),
                Duration.ofMinutes(10), clock);
    }

    @Bean(destroyMethod = "")
    HistoryCache historyCache(CacheProperties properties, EntraIdRedisCredentialsProvider credentials,
                              OpenTelemetry openTelemetry, Clock clock) {
        if (!properties.enabled()) {
            log.info("History cache disabled (sdp.cache.host not set)");
            return HistoryCache.NONE;
        }
        return new RedisHistoryCache(properties, credentials, openTelemetry, clock);
    }

    @Bean
    CacheLifecycle cacheLifecycle(HistoryCache cache, EntraIdRedisCredentialsProvider credentials) {
        return new CacheLifecycle(cache, credentials);
    }

    /** Verifies the Entra-authenticated connection at startup and keeps the token fresh. */
    static class CacheLifecycle implements AutoCloseable {

        private final HistoryCache cache;
        private final EntraIdRedisCredentialsProvider credentials;

        CacheLifecycle(HistoryCache cache, EntraIdRedisCredentialsProvider credentials) {
            this.cache = cache;
            this.credentials = credentials;
        }

        @EventListener(ApplicationReadyEvent.class)
        public void verify() {
            if (cache instanceof RedisHistoryCache redis) {
                redis.connectInBackground();
                Thread.ofVirtual().name("redis-verify").start(() -> {
                    try {
                        log.info("History cache connected with Microsoft Entra ID: PING -> {}", redis.ping());
                    } catch (Exception e) {
                        log.warn("History cache unavailable at startup, serving from the database: {}", e.toString());
                    }
                });
            }
        }

        @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
        public void refreshToken() {
            if (cache instanceof RedisHistoryCache) {
                credentials.refreshIfNeeded();
            }
        }

        @Override
        public void close() {
            if (cache instanceof RedisHistoryCache redis) {
                redis.close();
            }
        }
    }
}

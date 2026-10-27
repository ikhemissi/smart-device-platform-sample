package io.github.ikhemissi.sdp.platform;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Exposes the process-wide {@link OpenTelemetry} instance. In Azure it is installed by the Azure Monitor
 * OpenTelemetry (Application Insights) Java agent; without the agent it is a no-op. Tests can override the bean
 * with an SDK instance using in-memory exporters.
 */
@AutoConfiguration
public class PlatformObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OpenTelemetry openTelemetry() {
        return GlobalOpenTelemetry.get();
    }
}

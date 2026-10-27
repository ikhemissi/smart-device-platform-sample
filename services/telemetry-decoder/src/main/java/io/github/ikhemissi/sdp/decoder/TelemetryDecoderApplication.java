package io.github.ikhemissi.sdp.decoder;

import io.github.ikhemissi.sdp.decoder.ingest.IngestionService;
import io.github.ikhemissi.sdp.decoder.ingest.IngestionTelemetry;
import io.github.ikhemissi.sdp.decoder.ingest.TelemetryStore;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties(DecoderProperties.class)
public class TelemetryDecoderApplication {

    public static void main(String[] args) {
        SpringApplication.run(TelemetryDecoderApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    IngestionTelemetry ingestionTelemetry(OpenTelemetry openTelemetry) {
        return new IngestionTelemetry(openTelemetry);
    }

    @Bean
    IngestionService ingestionService(TelemetryStore store, IngestionTelemetry telemetry, DecoderProperties properties, Clock clock) {
        return new IngestionService(store, telemetry, properties.retry().initialBackoff(), properties.retry().maxBackoff(),
                duration -> Thread.sleep(duration), clock);
    }
}

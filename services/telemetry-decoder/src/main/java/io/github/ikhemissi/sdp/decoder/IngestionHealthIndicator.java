package io.github.ikhemissi.sdp.decoder;

import io.github.ikhemissi.sdp.decoder.ingest.IngestionService;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Reports DOWN while batches cannot be persisted (included in the readiness group). */
@Component("ingestion")
public class IngestionHealthIndicator implements HealthIndicator {

    private final IngestionService ingestion;

    public IngestionHealthIndicator(IngestionService ingestion) {
        this.ingestion = ingestion;
    }

    @Override
    public Health health() {
        IngestionService.Health state = ingestion.health();
        if (state.healthy()) {
            return Health.up().build();
        }
        return Health.down()
                .withDetail("failingSince", String.valueOf(state.failingSince()))
                .withDetail("lastError", String.valueOf(state.lastError()))
                .build();
    }
}

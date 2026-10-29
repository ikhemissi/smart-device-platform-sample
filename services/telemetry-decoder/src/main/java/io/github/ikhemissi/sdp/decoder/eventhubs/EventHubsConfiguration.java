package io.github.ikhemissi.sdp.decoder.eventhubs;

import com.azure.core.credential.TokenCredential;
import com.azure.messaging.eventhubs.EventProcessorClient;
import com.azure.messaging.eventhubs.EventProcessorClientBuilder;
import com.azure.messaging.eventhubs.checkpointstore.blob.BlobCheckpointStore;
import com.azure.messaging.eventhubs.models.EventPosition;
import com.azure.storage.blob.BlobContainerAsyncClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import io.github.ikhemissi.sdp.decoder.DecoderProperties;
import io.github.ikhemissi.sdp.decoder.ingest.IngestionService;
import io.github.ikhemissi.sdp.decoder.ingest.WatermarkStore;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Event Hubs event processor with a Blob Storage checkpoint store (M1 design D3). Azure access uses the
 * {@link TokenCredential} provided by Spring Cloud Azure: managed identity in Azure (client id from
 * {@code AZURE_CLIENT_ID}), developer identity locally.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sdp.decoder.processor.enabled", havingValue = "true", matchIfMissing = true)
public class EventHubsConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EventHubsConfiguration.class);

    @Bean
    BlobContainerAsyncClient checkpointContainer(DecoderProperties properties, TokenCredential credential) {
        DecoderProperties.Checkpoint checkpoint = properties.checkpoint();
        if (StringUtils.hasText(checkpoint.connectionString())) {
            BlobContainerAsyncClient client = new BlobContainerClientBuilder()
                    .connectionString(checkpoint.connectionString())
                    .containerName(checkpoint.containerName())
                    .buildAsyncClient();
            client.createIfNotExists().block();
            return client;
        }
        if (!StringUtils.hasText(checkpoint.containerUrl())) {
            throw new IllegalStateException("sdp.decoder.checkpoint.container-url is required");
        }
        return new BlobContainerClientBuilder().endpoint(checkpoint.containerUrl()).credential(credential).buildAsyncClient();
    }

    @Bean
    EventBatchHandler eventBatchHandler(IngestionService ingestion, WatermarkStore watermarks, Clock clock,
                                        DecoderProperties properties) {
        return new EventBatchHandler(ingestion, watermarks, clock, properties.batch().maxWait());
    }

    @Bean
    EventProcessorClient eventProcessorClient(DecoderProperties properties, TokenCredential credential,
                                              BlobContainerAsyncClient checkpointContainer, EventBatchHandler handler) {
        DecoderProperties.EventHubs eh = properties.eventhubs();
        EventProcessorClientBuilder builder = new EventProcessorClientBuilder()
                .consumerGroup(eh.consumerGroup())
                .checkpointStore(new BlobCheckpointStore(checkpointContainer))
                .processEventBatch(handler::onBatch, properties.batch().maxSize(), properties.batch().maxWait())
                .processPartitionInitialization(handler::onInitialization)
                .processError(handler::onError)
                // Without a checkpoint (first start, new consumer group or partition), read from the oldest retained event:
                // the SDK default (latest) would skip messages sent before the partition was first claimed.
                .initialPartitionEventPosition(partitionId -> EventPosition.earliest())
                // Tells whether a batch ends at the partition's last enqueued event (ingestion watermark).
                .trackLastEnqueuedEventProperties(true);
        if (StringUtils.hasText(eh.connectionString())) {
            builder.connectionString(eh.connectionString(), eh.eventHubName());
        } else if (StringUtils.hasText(eh.fullyQualifiedNamespace())) {
            builder.fullyQualifiedNamespace(eh.fullyQualifiedNamespace()).eventHubName(eh.eventHubName()).credential(credential);
        } else {
            throw new IllegalStateException("sdp.decoder.eventhubs.fully-qualified-namespace is required");
        }
        return builder.buildEventProcessorClient();
    }

    @Bean
    SmartLifecycle eventProcessorLifecycle(EventProcessorClient processor, IngestionService ingestion,
                                           DecoderProperties properties) {
        return new SmartLifecycle() {
            @Override
            public void start() {
                log.info("Starting event processor: hub={} consumerGroup={} batch={}",
                        properties.eventhubs().eventHubName(), properties.eventhubs().consumerGroup(), properties.batch());
                processor.start();
            }

            @Override
            public void stop() {
                ingestion.stop();
                processor.stop();
                log.info("Event processor stopped");
            }

            @Override
            public boolean isRunning() {
                return processor.isRunning();
            }
        };
    }
}

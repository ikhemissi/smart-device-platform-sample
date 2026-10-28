package io.github.ikhemissi.sdp.decoder.servicebus;

import com.azure.core.credential.TokenCredential;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import io.github.ikhemissi.sdp.decoder.DecoderProperties;
import io.github.ikhemissi.sdp.decoder.ingest.MeasurementPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Publication of persisted measurements for alerting (M5 design D1), enabled by {@code ALERTING_PUBLISH_ENABLED} so the
 * decoder can run before the topic exists. In Azure the namespace is addressed by host name with the decoder's managed
 * identity; a connection string is accepted only for the local Service Bus emulator.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sdp.decoder.publication.enabled", havingValue = "true")
public class ServiceBusConfiguration {

    @Bean(destroyMethod = "close")
    ServiceBusSenderClient measurementsSender(DecoderProperties properties, TokenCredential credential) {
        DecoderProperties.Publication publication = properties.publication();
        ServiceBusClientBuilder builder = new ServiceBusClientBuilder();
        if (StringUtils.hasText(publication.connectionString())) {
            builder.connectionString(publication.connectionString());
        } else if (StringUtils.hasText(publication.fullyQualifiedNamespace())) {
            builder.fullyQualifiedNamespace(publication.fullyQualifiedNamespace()).credential(credential);
        } else {
            throw new IllegalStateException("sdp.decoder.publication.fully-qualified-namespace is required when publication is enabled");
        }
        return builder.sender().topicName(publication.topic()).buildClient();
    }

    @Bean
    MeasurementPublisher measurementPublisher(ServiceBusSenderClient measurementsSender) {
        return new ServiceBusMeasurementPublisher(measurementsSender);
    }
}

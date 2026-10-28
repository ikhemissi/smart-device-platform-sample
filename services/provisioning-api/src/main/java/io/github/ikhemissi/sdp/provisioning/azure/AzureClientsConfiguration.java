package io.github.ikhemissi.sdp.provisioning.azure;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import io.github.ikhemissi.sdp.provisioning.ProvisioningProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Clients authenticated with the platform's credential: managed identity in Azure, developer identity locally. */
@Configuration(proxyBeanMethods = false)
class AzureClientsConfiguration {

    @Bean
    @ConditionalOnMissingBean
    EnrollmentGroups enrollmentGroups(ProvisioningProperties properties, ObjectProvider<TokenCredential> credential) {
        return new RestEnrollmentGroups(require(properties.dpsHost(), "sdp.provisioning.dps-host"), credential(credential));
    }

    @Bean
    @ConditionalOnMissingBean
    DpsCertificates dpsCertificates(ProvisioningProperties properties, ObjectProvider<TokenCredential> credential) {
        return new ArmDpsCertificates(require(properties.dpsResourceId(), "sdp.provisioning.dps-resource-id"), credential(credential));
    }

    @Bean
    @ConditionalOnMissingBean
    DeviceRegistry deviceRegistry(ProvisioningProperties properties, ObjectProvider<TokenCredential> credential) {
        return new RestDeviceRegistry(require(properties.iotHubHost(), "sdp.provisioning.iothub-host"), credential(credential));
    }

    private static TokenCredential credential(ObjectProvider<TokenCredential> credential) {
        return credential.getIfAvailable(() -> new DefaultAzureCredentialBuilder().build());
    }

    private static String require(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " must be set");
        }
        return value;
    }
}

package io.github.ikhemissi.sdp.deviceapi;

import io.github.ikhemissi.sdp.platform.security.SdpSecurityProperties;
import io.github.ikhemissi.sdp.platform.security.SdpTokens;
import io.github.ikhemissi.sdp.testsupport.AccessTokens;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** Verifies test tokens with the local key instead of the tenant's signing keys; issuer and audience rules unchanged. */
@TestConfiguration(proxyBeanMethods = false)
public class TestSecurity {

    public static final String[] PROPERTIES = {
            "sdp.security.tenant-id=" + AccessTokens.TENANT,
            "sdp.security.audiences=" + AccessTokens.CLIENT_ID};

    @Bean
    JwtDecoder testJwtDecoder(SdpSecurityProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(AccessTokens.publicKey()).build();
        decoder.setJwtValidator(SdpTokens.validator(properties));
        return decoder;
    }
}

package io.github.ikhemissi.sdp.provisioning;

import io.github.ikhemissi.sdp.platform.security.SdpSecurityProperties;
import io.github.ikhemissi.sdp.platform.security.SdpTokens;
import io.github.ikhemissi.sdp.testsupport.AccessTokens;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** Test tokens verified with the local key; Azure replaced by in-memory fakes. */
@TestConfiguration(proxyBeanMethods = false)
public class TestSecurity {

    public static final FakeAzure AZURE = new FakeAzure();

    @Bean
    JwtDecoder testJwtDecoder(SdpSecurityProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(AccessTokens.publicKey()).build();
        decoder.setJwtValidator(SdpTokens.validator(properties));
        return decoder;
    }

    @Bean
    @Primary
    FakeAzure fakeAzure() {
        return AZURE;
    }
}

package io.github.ikhemissi.sdp.platform.security;

import io.github.ikhemissi.sdp.testsupport.AccessTokens;

import java.util.Map;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/** Minimal service using the platform security, with a local signing key instead of Entra's JWKS endpoint. */
@SpringBootApplication
class SecurityTestApplication {

    @Bean
    JwtDecoder testJwtDecoder(SdpSecurityProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(AccessTokens.publicKey()).build();
        decoder.setJwtValidator(SdpTokens.validator(properties));
        return decoder;
    }

    @RestController
    static class MeController {
        @GetMapping("/me")
        @RequiredRole(SdpRole.AUTHENTICATED)
        SdpUser me() {
            return SdpUser.current();
        }
    }

    @RestController
    @RequiredRole(SdpRole.READ)
    static class ItemsController {
        @GetMapping("/items")
        Map<String, String> list() {
            return Map.of("status", "listed");
        }

        @PutMapping("/items")
        @RequiredRole(SdpRole.WRITE)
        Map<String, String> update() {
            return Map.of("status", "updated");
        }
    }

    @RestController
    static class UndeclaredController {
        @GetMapping("/undeclared")
        Map<String, String> undeclared() {
            return Map.of("status", "exposed");
        }
    }
}

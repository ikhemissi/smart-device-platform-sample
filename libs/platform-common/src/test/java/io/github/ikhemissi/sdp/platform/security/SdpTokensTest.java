package io.github.ikhemissi.sdp.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class SdpTokensTest {

    @Test
    void writeRoleGrantsBothAuthorities() {
        assertThat(authorities(List.of("DeviceData.Write"))).containsExactly("DeviceData.Read", "DeviceData.Write");
    }

    @Test
    void readRoleGrantsReadOnly() {
        assertThat(authorities(List.of("DeviceData.Read"))).containsExactly("DeviceData.Read");
    }

    @Test
    void bothRolesAreNotDuplicated() {
        assertThat(authorities(List.of("DeviceData.Read", "DeviceData.Write")))
                .containsExactly("DeviceData.Read", "DeviceData.Write");
    }

    @Test
    void missingOrUnknownRolesGrantNothing() {
        assertThat(authorities(null)).isEmpty();
        assertThat(authorities(List.of("Something.Else"))).isEmpty();
    }

    @Test
    void principalIsTheObjectId() {
        var authentication = SdpTokens.authenticationConverter().convert(jwt(List.of("DeviceData.Read")));

        assertThat(authentication.getName()).isEqualTo("oid-1");
    }

    private static List<String> authorities(List<String> roles) {
        return SdpTokens.authorities(jwt(roles)).stream().map(GrantedAuthority::getAuthority).toList();
    }

    private static Jwt jwt(List<String> roles) {
        var builder = Jwt.withTokenValue("t").header("alg", "RS256").claim("oid", "oid-1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (roles != null) {
            builder.claim("roles", roles);
        }
        return builder.build();
    }
}

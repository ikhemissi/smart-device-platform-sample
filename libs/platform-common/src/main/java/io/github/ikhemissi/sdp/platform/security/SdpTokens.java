package io.github.ikhemissi.sdp.platform.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/** Entra ID access-token rules shared by platform APIs. */
public final class SdpTokens {

    static final String ROLES_CLAIM = "roles";
    static final String OBJECT_ID_CLAIM = "oid";

    private SdpTokens() {
    }

    /** Signature-independent checks: timestamps, tenant issuer and API audience. */
    public static OAuth2TokenValidator<Jwt> validator(SdpSecurityProperties properties) {
        Set<String> audiences = Set.copyOf(properties.audiences());
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                aud -> aud != null && aud.stream().anyMatch(audiences::contains));
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(properties.issuer()), audience);
    }

    /** Principal = user object id; authorities = app roles, where {@code DeviceData.Write} implies {@code DeviceData.Read}. */
    public static JwtAuthenticationConverter authenticationConverter() {
        var converter = new JwtAuthenticationConverter();
        converter.setPrincipalClaimName(OBJECT_ID_CLAIM);
        converter.setJwtGrantedAuthoritiesConverter(SdpTokens::authorities);
        return converter;
    }

    static Collection<GrantedAuthority> authorities(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList(ROLES_CLAIM);
        if (roles == null) {
            return List.of();
        }
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        for (String role : roles) {
            if (SdpRole.WRITE.appRole().equals(role)) {
                authorities.add(new SimpleGrantedAuthority(SdpRole.READ.appRole()));
                authorities.add(new SimpleGrantedAuthority(SdpRole.WRITE.appRole()));
            } else if (SdpRole.READ.appRole().equals(role)) {
                authorities.add(new SimpleGrantedAuthority(SdpRole.READ.appRole()));
            }
        }
        return List.copyOf(authorities);
    }
}

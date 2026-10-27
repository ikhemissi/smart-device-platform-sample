package io.github.ikhemissi.sdp.platform.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The signed-in user, as asserted by the access token. Used for {@code GET /me} and to attribute changes.
 *
 * @param objectId          Entra object id ({@code oid})
 * @param displayName       {@code name} claim (falls back to the UPN)
 * @param userPrincipalName {@code preferred_username} claim
 */
public record SdpUser(String objectId, String displayName, String userPrincipalName, boolean canRead, boolean canWrite) {

    public static SdpUser from(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            throw new IllegalStateException("Not authenticated with an access token");
        }
        var jwt = token.getToken();
        String upn = jwt.getClaimAsString("preferred_username");
        String name = jwt.getClaimAsString("name");
        return new SdpUser(
                jwt.getClaimAsString(SdpTokens.OBJECT_ID_CLAIM),
                name != null && !name.isBlank() ? name : upn,
                upn,
                has(authentication, SdpRole.READ),
                has(authentication, SdpRole.WRITE));
    }

    public static SdpUser current() {
        return from(SecurityContextHolder.getContext().getAuthentication());
    }

    static boolean has(Authentication authentication, SdpRole role) {
        if (role.appRole() == null) {
            return authentication != null && authentication.isAuthenticated();
        }
        return authentication != null && authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(role.appRole()::equals);
    }
}

package io.github.ikhemissi.sdp.testsupport;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Issues RS256 access tokens shaped like Microsoft Entra ID v2 access tokens for a test tenant and API. Services under
 * test verify them with {@link #publicKey()} instead of the tenant's signing keys.
 */
public final class AccessTokens {

    public static final String TENANT = "11111111-2222-3333-4444-555555555555";
    public static final String CLIENT_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    public static final String ISSUER = "https://login.microsoftonline.com/" + TENANT + "/v2.0";
    public static final String USER_OID = "6b337bd5-0000-0000-0000-000000000001";

    private static final KeyPair KEYS = generate();
    private static final KeyPair OTHER_KEYS = generate();

    private AccessTokens() {
    }

    public static RSAPublicKey publicKey() {
        return (RSAPublicKey) KEYS.getPublic();
    }

    /** A valid token for the test user holding the given app roles (none: a signed-in user without permission). */
    public static String withRoles(String... roles) {
        return token(claims -> claims.claim("roles", roles.length == 0 ? null : List.of(roles)), KEYS);
    }

    public static String custom(UnaryOperator<JWTClaimsSet.Builder> customizer) {
        return token(customizer, KEYS);
    }

    public static String signedByUnknownKey() {
        return token(UnaryOperator.identity(), OTHER_KEYS);
    }

    private static String token(UnaryOperator<JWTClaimsSet.Builder> customizer, KeyPair keys) {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(CLIENT_ID)
                .subject("pairwise-subject")
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .claim("oid", USER_OID)
                .claim("tid", TENANT)
                .claim("name", "Ada Lovelace")
                .claim("preferred_username", "ada@contoso.example")
                .claim("scp", "user_impersonation")
                .claim("ver", "2.0");
        try {
            var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), customizer.apply(claims).build());
            jwt.sign(new RSASSASigner(keys.getPrivate()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair generate() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

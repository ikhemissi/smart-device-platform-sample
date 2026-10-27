package io.github.ikhemissi.sdp.platform.security;

import io.github.ikhemissi.sdp.testsupport.AccessTokens;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@SpringBootTest(classes = SecurityTestApplication.class, properties = {
        "sdp.security.tenant-id=" + AccessTokens.TENANT,
        "sdp.security.audiences=" + AccessTokens.CLIENT_ID + ", api://" + AccessTokens.CLIENT_ID})
@AutoConfigureMockMvc
class SdpSecurityAutoConfigurationTest {

    private static final String READ = "DeviceData.Read";
    private static final String WRITE = "DeviceData.Write";

    @Autowired
    private MockMvcTester mvc;

    @Test
    void missingTokenIsUnauthorizedWithProblemDetails() {
        MvcTestResult result = mvc.get().uri("/items").exchange();

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED)
                .hasContentTypeCompatibleWith("application/problem+json");
        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(401);
        assertThat(result).bodyJson().extractingPath("$.instance").isEqualTo("/items");
    }

    @Test
    void tokenSignedByUnknownKeyIsUnauthorized() {
        assertThat(get("/items", AccessTokens.signedByUnknownKey())).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void tokenForAnotherAudienceIsUnauthorized() {
        String token = AccessTokens.custom(c -> c.audience("api://another-api").claim("roles", java.util.List.of(READ)));

        MvcTestResult result = get("/items", token);

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).contains("invalid_token");
    }

    @Test
    void identifierUriAudienceIsAccepted() {
        String token = AccessTokens.custom(c -> c.audience("api://" + AccessTokens.CLIENT_ID).claim("roles", java.util.List.of(READ)));

        assertThat(get("/items", token)).hasStatus(HttpStatus.OK);
    }

    @Test
    void tokenFromAnotherTenantIsUnauthorized() {
        String token = AccessTokens.custom(c -> c.issuer("https://login.microsoftonline.com/other-tenant/v2.0")
                .claim("roles", java.util.List.of(READ)));

        assertThat(get("/items", token)).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void expiredTokenIsUnauthorized() {
        Instant past = Instant.now().minusSeconds(7200);
        String token = AccessTokens.custom(c -> c.issueTime(Date.from(past)).notBeforeTime(Date.from(past))
                .expirationTime(Date.from(past.plusSeconds(3600))).claim("roles", java.util.List.of(READ)));

        assertThat(get("/items", token)).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void readerCanReadButNotWrite() {
        String token = AccessTokens.withRoles(READ);

        assertThat(get("/items", token)).hasStatus(HttpStatus.OK);

        MvcTestResult update = mvc.put().uri("/items").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
        assertThat(update).hasStatus(HttpStatus.FORBIDDEN).hasContentTypeCompatibleWith("application/problem+json");
        assertThat(update).bodyJson().extractingPath("$.requiredRole").isEqualTo(WRITE);
        assertThat(update).bodyJson().extractingPath("$.detail").asString().contains(WRITE);
    }

    @Test
    void writerImpliesReader() {
        String token = AccessTokens.withRoles(WRITE);

        assertThat(get("/items", token)).hasStatus(HttpStatus.OK);
        assertThat(mvc.put().uri("/items").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange())
                .hasStatus(HttpStatus.OK);
    }

    @Test
    void userWithoutRoleIsForbiddenFromData() {
        MvcTestResult result = get("/items", AccessTokens.withRoles());

        assertThat(result).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(result).bodyJson().extractingPath("$.requiredRole").isEqualTo(READ);
    }

    @Test
    void userWithoutRoleCanReadOwnPermissions() {
        MvcTestResult result = get("/me", AccessTokens.withRoles());

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.objectId").isEqualTo(AccessTokens.USER_OID);
        assertThat(result).bodyJson().extractingPath("$.displayName").isEqualTo("Ada Lovelace");
        assertThat(result).bodyJson().extractingPath("$.userPrincipalName").isEqualTo("ada@contoso.example");
        assertThat(result).bodyJson().extractingPath("$.canRead").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.canWrite").isEqualTo(false);
    }

    @Test
    void editorPermissionsAreReported() {
        MvcTestResult result = get("/me", AccessTokens.withRoles(WRITE));

        assertThat(result).bodyJson().extractingPath("$.canRead").isEqualTo(true);
        assertThat(result).bodyJson().extractingPath("$.canWrite").isEqualTo(true);
    }

    @Test
    void unknownRolesGrantNothing() {
        assertThat(get("/items", AccessTokens.withRoles("Admin", "DeviceData.ReadWrite"))).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void handlerWithoutDeclaredRoleIsRefused() {
        assertThat(get("/undeclared", AccessTokens.withRoles(WRITE))).hasStatus(HttpStatus.FORBIDDEN);
    }

    private MvcTestResult get(String path, String token) {
        return mvc.get().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }
}

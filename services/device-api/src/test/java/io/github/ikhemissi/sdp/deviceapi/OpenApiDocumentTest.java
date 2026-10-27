package io.github.ikhemissi.sdp.deviceapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ikhemissi.sdp.testsupport.AccessTokens;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Exports the OpenAPI document imported by API Management to {@code openapi.json} and fails when the committed file
 * is out of date. Regenerate with {@code ./mvnw -pl services/device-api test -Dsdp.openapi.update=true}.
 */
@SpringBootTest(properties = {
        "springdoc.api-docs.enabled=true",
        "spring.datasource.url=jdbc:postgresql://localhost:1/unused",
        "spring.datasource.azure.passwordless-enabled=false",
        "sdp.security.tenant-id=" + AccessTokens.TENANT,
        "sdp.security.audiences=" + AccessTokens.CLIENT_ID})
@AutoConfigureMockMvc
@Import(TestSecurity.class)
class OpenApiDocumentTest {

    private static final Path EXPORT = Path.of("openapi.json");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(tools.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    @Autowired
    private MockMvcTester mvc;

    @Test
    void exportedDocumentIsUpToDate() throws IOException {
        String generated = JSON.writeValueAsString(generate()) + "\n";

        if (Boolean.getBoolean("sdp.openapi.update") || !Files.exists(EXPORT)) {
            Files.writeString(EXPORT, generated);
        }
        assertThat(Files.readString(EXPORT))
                .as("openapi.json is out of date; regenerate with -Dsdp.openapi.update=true")
                .isEqualTo(generated);
    }

    @Test
    void everyOperationDeclaresItsRole() {
        JsonNode paths = generate().get("paths");
        paths.properties().forEach(path -> path.getValue().properties().forEach(operation ->
                assertThat(operation.getValue().path("x-sdp-required-role").asString())
                        .as(operation.getKey() + " " + path.getKey())
                        .isIn("authenticated", "read", "write")));
        assertThat(paths.get("/api/v1/devices/{deviceId}").get("patch").get("x-sdp-required-role").asString()).isEqualTo("write");
        assertThat(paths.get("/api/v1/me").get("get").get("x-sdp-required-role").asString()).isEqualTo("authenticated");
    }

    private ObjectNode generate() {
        try {
            String body = mvc.get().uri("/v3/api-docs")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + AccessTokens.withRoles())
                    .exchange().getResponse().getContentAsString();
            ObjectNode document = (ObjectNode) JSON.readTree(body);
            document.remove("servers");
            return document;
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}

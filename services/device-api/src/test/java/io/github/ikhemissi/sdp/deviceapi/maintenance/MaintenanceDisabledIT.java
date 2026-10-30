package io.github.ikhemissi.sdp.deviceapi.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ikhemissi.sdp.deviceapi.CatalogDatabase;
import io.github.ikhemissi.sdp.deviceapi.TestSecurity;
import io.github.ikhemissi.sdp.testsupport.AccessTokens;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

/** M9 task 3.1 — maintenance switched off (spec predictive-maintenance: "Availability per environment"). */
@SpringBootTest(properties = {
        "sdp.security.tenant-id=" + AccessTokens.TENANT,
        "sdp.security.audiences=" + AccessTokens.CLIENT_ID,
        "sdp.maintenance.enabled=false"})
@AutoConfigureMockMvc
@Import(TestSecurity.class)
class MaintenanceDisabledIT {

    private static long item;

    @Autowired
    private MockMvcTester mvc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        CatalogDatabase.register(registry);
    }

    @BeforeAll
    static void seed() {
        JdbcTemplate admin = CatalogDatabase.admin();
        admin.update("INSERT INTO sdp.sites (site_id, display_name) VALUES ('mt-off-01', 'Off') ON CONFLICT DO NOTHING");
        admin.update("""
                INSERT INTO sdp.devices (device_id, site_id, display_name, last_payload_format, first_seen_at, last_seen_at)
                VALUES ('mt-off-1', 'mt-off-01', 'Cold room', 'ruuvi-df5', now(), now()) ON CONFLICT DO NOTHING""");
        item = MaintenanceApiIT.item(admin, "mt-off-1", "mt-off-01", "open", 0.9, null, null);
    }

    @Test
    void nothingIsListedNorAssessed() {
        assertThat(MaintenanceApiIT.json(get("/api/v1/maintenance")).isEmpty()).isTrue();
        assertThat(MaintenanceApiIT.json(get("/api/v1/maintenance?siteId=mt-off-01")).isEmpty()).isTrue();
        JsonNode device = MaintenanceApiIT.json(get("/api/v1/devices/mt-off-1/maintenance"));
        assertThat(device.get("enabled").asBoolean()).isFalse();
        assertThat(device.get("assessment").isNull()).isTrue();
        MvcTestResult status = mvc.post().uri("/api/v1/maintenance/" + item + "/status")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + MaintenanceApiIT.EDITOR)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\": \"done\"}").exchange();
        assertThat(status).hasStatus(HttpStatus.NOT_FOUND);
    }

    private MvcTestResult get(String path) {
        return mvc.get().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + MaintenanceApiIT.READER).exchange();
    }
}

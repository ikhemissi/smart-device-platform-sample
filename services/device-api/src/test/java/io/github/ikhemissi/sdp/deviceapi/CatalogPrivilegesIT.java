package io.github.ikhemissi.sdp.deviceapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** The device API's database role may read the catalog and telemetry but only edit device information. */
class CatalogPrivilegesIT {

    private static final JdbcTemplate CATALOG = CatalogDatabase.asCatalog();

    @BeforeAll
    static void seed() {
        CatalogDatabase.admin().update("INSERT INTO sdp.sites (site_id, display_name) VALUES ('priv-01', 'Privileges') ON CONFLICT DO NOTHING");
        CatalogDatabase.admin().update("""
                INSERT INTO sdp.devices (device_id, site_id, last_payload_format, first_seen_at, last_seen_at)
                VALUES ('priv-dev', 'priv-01', 'ruuvi-df5', now(), now()) ON CONFLICT DO NOTHING""");
    }

    @Test
    void canReadCatalogAndTelemetry() {
        assertThat(CATALOG.queryForObject("SELECT count(*) FROM sdp.devices", Long.class)).isPositive();
        assertThat(CATALOG.queryForObject("SELECT count(*) FROM sdp.measurements", Long.class)).isNotNull();
        assertThat(CATALOG.queryForObject("SELECT count(*) FROM sdp.metric_definitions", Long.class)).isNotNull();
    }

    @Test
    void canUpdateEditableColumns() {
        int updated = CATALOG.update("""
                UPDATE sdp.devices SET display_name = 'Kitchen', description = 'd', location = '{"latitude":1,"longitude":2}',
                       updated_at = now(), updated_by = 'oid', updated_by_name = 'Ada' WHERE device_id = 'priv-dev'""");
        assertThat(updated).isEqualTo(1);
    }

    @Test
    void cannotChangeIdentityOrIngestionFields() {
        assertThatThrownBy(() -> CATALOG.update("UPDATE sdp.devices SET site_id = 'priv-01' WHERE device_id = 'priv-dev'"))
                .isInstanceOf(DataAccessException.class).hasRootCauseMessage("ERROR: permission denied for table devices");
        assertThatThrownBy(() -> CATALOG.update("UPDATE sdp.devices SET last_seen_at = now() WHERE device_id = 'priv-dev'"))
                .isInstanceOf(DataAccessException.class).hasRootCauseMessage("ERROR: permission denied for table devices");
    }

    @Test
    void cannotInsertOrDelete() {
        assertThatThrownBy(() -> CATALOG.update("DELETE FROM sdp.devices WHERE device_id = 'priv-dev'"))
                .isInstanceOf(DataAccessException.class).hasRootCauseMessage("ERROR: permission denied for table devices");
        assertThatThrownBy(() -> CATALOG.update("INSERT INTO sdp.sites (site_id, display_name) VALUES ('priv-02', 'x')"))
                .isInstanceOf(DataAccessException.class).hasRootCauseMessage("ERROR: permission denied for table sites");
        assertThatThrownBy(() -> CATALOG.queryForObject("SELECT count(*) FROM sdp.ingested_messages", Long.class))
                .isInstanceOf(DataAccessException.class).hasRootCauseMessage("ERROR: permission denied for table ingested_messages");
    }
}

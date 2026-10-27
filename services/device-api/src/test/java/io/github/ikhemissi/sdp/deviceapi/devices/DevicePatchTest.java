package io.github.ikhemissi.sdp.deviceapi.devices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ikhemissi.sdp.deviceapi.web.ApiException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class DevicePatchTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DeviceRecord CURRENT = new DeviceRecord("dev-1", "lyon-01", "Old name", "Old description",
            new Location(45.76, 4.83, "Lyon"), "ruuvi-df5", Instant.EPOCH, Instant.EPOCH, null, null, null);

    @Test
    void absentFieldsAreUnchanged() {
        var result = apply("{}");

        assertThat(result).isEqualTo(new DevicePatch.Result("Old name", "Old description", CURRENT.location()));
    }

    @Test
    void nullAndBlankClearFields() {
        var result = apply("{\"displayName\":\"   \",\"description\":null,\"location\":null}");

        assertThat(result).isEqualTo(new DevicePatch.Result(null, null, null));
    }

    @Test
    void locationIsMerged() {
        var result = apply("{\"location\":{\"label\":null,\"longitude\":-180}}");

        assertThat(result.location()).isEqualTo(new Location(45.76, -180, null));
    }

    @Test
    void newLocationNeedsBothCoordinates() {
        var noLocation = new DeviceRecord("d", "s", null, null, null, "f", Instant.EPOCH, Instant.EPOCH, null, null, null);

        assertThatThrownBy(() -> DevicePatch.apply(JSON.readTree("{\"location\":{\"latitude\":1}}"), noLocation))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errors()).containsKey("location.longitude"));
    }

    @Test
    void allErrorsAreReportedTogether() {
        assertThatThrownBy(() -> apply("""
                {"deviceId":"x","lastSeenAt":"now","displayName":42,"description":"%s",
                 "location":{"latitude":"north","longitude":200,"altitude":3}}""".formatted("d".repeat(1001))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errors()).containsOnlyKeys(
                        "deviceId", "lastSeenAt", "displayName", "description", "location.latitude",
                        "location.longitude", "location.altitude"));
    }

    @Test
    void bodyMustBeAnObject() {
        assertThatThrownBy(() -> apply("[]")).isInstanceOf(ApiException.class);
    }

    @Test
    void ifMatchAcceptsListsWeakTagsAndWildcard() {
        assertThat(DeviceService.matches("\"a\", W/\"b\"", "\"b\"")).isTrue();
        assertThat(DeviceService.matches("*", "\"b\"")).isTrue();
        assertThat(DeviceService.matches("\"a\"", "\"b\"")).isFalse();
    }

    @Test
    void entityTagIgnoresTelemetryFields() {
        var seenLater = new DeviceRecord("dev-1", "lyon-01", "Old name", "Old description", CURRENT.location(),
                "bthome-v2", Instant.EPOCH, Instant.now(), null, null, null);

        assertThat(seenLater.entityTag()).isEqualTo(CURRENT.entityTag());
    }

    private static DevicePatch.Result apply(String json) {
        return DevicePatch.apply(JSON.readTree(json), CURRENT);
    }
}

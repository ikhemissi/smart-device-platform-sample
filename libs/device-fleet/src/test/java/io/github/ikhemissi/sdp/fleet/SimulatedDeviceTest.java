package io.github.ikhemissi.sdp.fleet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ikhemissi.sdp.codecs.PayloadFormat;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Spec device-simulation: "Configurable fleet" and "Device identity and provisioning" (stable identities). */
class SimulatedDeviceTest {

    private static final List<PayloadFormat> ALL = List.of(PayloadFormat.values());

    @Test
    void defaultFleetHas30DevicesOver3SitesUsingAllFormats() {
        List<SimulatedDevice> fleet = SimulatedDevice.plan(List.of("paris-01", "lyon-01", "marseille-01"), 10, ALL);

        assertThat(fleet).hasSize(30);
        assertThat(fleet.stream().collect(Collectors.groupingBy(SimulatedDevice::siteId, Collectors.counting())))
                .containsOnly(java.util.Map.entry("paris-01", 10L), java.util.Map.entry("lyon-01", 10L), java.util.Map.entry("marseille-01", 10L));
        assertThat(fleet.stream().map(SimulatedDevice::format).distinct()).containsExactlyInAnyOrderElementsOf(ALL);
        assertThat(fleet.stream().map(SimulatedDevice::deviceId)).doesNotHaveDuplicates()
                .contains("paris-01-ruuvi5-001", "paris-01-ruuvi5-003", "lyon-01-ruuvi3-001", "marseille-01-bthome-002", "paris-01-cayenne-002")
                .allMatch(id -> id.matches("^[a-z0-9-]+-(ruuvi5|ruuvi3|bthome|cayenne)-\\d{3}$"));
    }

    @Test
    void customFleet() {
        List<String> sites = List.of("s-1", "s-2", "s-3", "s-4", "s-5");

        assertThat(SimulatedDevice.plan(sites, 20, ALL)).hasSize(100);
    }

    @Test
    void identitiesAreStableAcrossRestarts() {
        assertThat(SimulatedDevice.plan(List.of("paris-01"), 7, ALL)).isEqualTo(SimulatedDevice.plan(List.of("paris-01"), 7, ALL));
    }

    @Test
    void repeatingAFormatWeightsTheMix() {
        List<SimulatedDevice> fleet = SimulatedDevice.plan(List.of("paris-01"), 9,
                List.of(PayloadFormat.RUUVI_DF5, PayloadFormat.RUUVI_DF5, PayloadFormat.CAYENNE_LPP));

        assertThat(fleet.stream().filter(d -> d.format() == PayloadFormat.RUUVI_DF5)).hasSize(6);
        assertThat(fleet.stream().filter(d -> d.format() == PayloadFormat.CAYENNE_LPP)).hasSize(3);
    }

    @Test
    void invalidFleetIsRefused() {
        assertThatThrownBy(() -> SimulatedDevice.plan(List.of(), 10, ALL)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SimulatedDevice.plan(List.of("a-1"), 0, ALL)).isInstanceOf(IllegalArgumentException.class);
    }
}

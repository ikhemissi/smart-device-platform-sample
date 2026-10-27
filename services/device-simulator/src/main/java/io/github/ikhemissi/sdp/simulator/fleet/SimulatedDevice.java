package io.github.ikhemissi.sdp.simulator.fleet;

import io.github.ikhemissi.sdp.codecs.PayloadFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A simulated device: id {@code <siteId>-<format short name>-<nnn>} (spec device-simulation). */
public record SimulatedDevice(String deviceId, String siteId, PayloadFormat format) {

    public static String shortName(PayloadFormat format) {
        return switch (format) {
            case RUUVI_DF5 -> "ruuvi5";
            case RUUVI_DF3 -> "ruuvi3";
            case BTHOME_V2 -> "bthome";
            case CAYENNE_LPP -> "cayenne";
        };
    }

    /**
     * Deterministic fleet: for each site, devices are assigned formats round-robin from {@code formatMix} (repeat a
     * format to weight it) and numbered per site and format, so restarts reuse the same identities.
     */
    public static List<SimulatedDevice> plan(List<String> sites, int devicesPerSite, List<PayloadFormat> formatMix) {
        if (sites.isEmpty() || devicesPerSite <= 0 || formatMix.isEmpty()) {
            throw new IllegalArgumentException("at least one site, one device per site and one format are required");
        }
        List<SimulatedDevice> fleet = new ArrayList<>();
        for (String site : sites) {
            Map<PayloadFormat, Integer> counters = new HashMap<>();
            for (int i = 0; i < devicesPerSite; i++) {
                PayloadFormat format = formatMix.get(i % formatMix.size());
                int n = counters.merge(format, 1, Integer::sum);
                fleet.add(new SimulatedDevice("%s-%s-%03d".formatted(site, shortName(format), n), site, format));
            }
        }
        return List.copyOf(fleet);
    }
}

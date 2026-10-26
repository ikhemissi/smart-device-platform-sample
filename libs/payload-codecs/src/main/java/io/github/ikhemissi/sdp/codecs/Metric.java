package io.github.ikhemissi.sdp.codecs;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Canonical metrics with their fixed units (ADR-0003). Metric keys in decoded results are the canonical
 * {@link #key()}, optionally suffixed with {@code _ch<channel>} when a Cayenne LPP payload reports the same
 * metric on several channels.
 */
public enum Metric {
    TEMPERATURE("temperature", "°C", "Air temperature"),
    HUMIDITY("humidity", "%", "Relative humidity"),
    PRESSURE("pressure", "hPa", "Atmospheric pressure"),
    ACCELERATION_X("acceleration_x", "g", "Acceleration on the X axis"),
    ACCELERATION_Y("acceleration_y", "g", "Acceleration on the Y axis"),
    ACCELERATION_Z("acceleration_z", "g", "Acceleration on the Z axis"),
    BATTERY_VOLTAGE("battery_voltage", "V", "Battery voltage"),
    BATTERY_LEVEL("battery_level", "%", "Battery level"),
    TX_POWER("tx_power", "dBm", "Radio transmission power"),
    MOVEMENT_COUNTER("movement_counter", "", "Number of movements detected by the accelerometer"),
    MEASUREMENT_SEQUENCE("measurement_sequence", "", "Device measurement sequence number"),
    PACKET_ID("packet_id", "", "Device packet identifier"),
    CO2("co2", "ppm", "Carbon dioxide concentration"),
    ILLUMINANCE("illuminance", "lx", "Illuminance"),
    ANALOG_INPUT("analog_input", "", "Generic analog input"),
    ANALOG_OUTPUT("analog_output", "", "Generic analog output"),
    DIGITAL_INPUT("digital_input", "", "Generic digital input"),
    DIGITAL_OUTPUT("digital_output", "", "Generic digital output"),
    PRESENCE("presence", "", "Presence detected (1) or not (0)"),
    LATITUDE("latitude", "°", "GPS latitude"),
    LONGITUDE("longitude", "°", "GPS longitude"),
    ALTITUDE("altitude", "m", "GPS altitude");

    private static final Map<String, Metric> BY_KEY =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(Metric::key, Function.identity()));
    private static final Pattern CHANNEL_SUFFIX = Pattern.compile("_ch\\d{1,3}$");

    private final String key;
    private final String unit;
    private final String description;

    Metric(String key, String unit, String description) {
        this.key = key;
        this.unit = unit;
        this.description = description;
    }

    public String key() {
        return key;
    }

    /** Unit symbol; empty for dimensionless values. */
    public String unit() {
        return unit;
    }

    public String description() {
        return description;
    }

    /** Resolves a metric key, ignoring a Cayenne {@code _ch<n>} channel suffix. */
    public static Optional<Metric> fromKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_KEY.get(CHANNEL_SUFFIX.matcher(key).replaceFirst("")));
    }
}

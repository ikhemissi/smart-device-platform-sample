package io.github.ikhemissi.sdp.fleet;

import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Plausible, continuous readings for one device (spec device-simulation: "Realistic readings"). A per-device seed
 * gives each device stable baselines; temperature and humidity follow a daily cycle with small noise, pressure drifts
 * slowly like the weather (computed from the time, so that it stays continuous when the simulator restarts), battery discharges slowly and counters increase. Values stay within each format's valid range.
 */
public final class ReadingModel {

    public record Reading(Map<String, Number> metrics, Map<String, String> attributes) {
    }

    private static final double HOURS_PER_DAY = 24.0;
    // Weather-like pressure drift: three slow waves (at most +/-7.5 hPa, about 1 hPa per hour at the steepest).
    private static final double[] PRESSURE_PERIODS_HOURS = {31, 53, 97};
    private static final double[] PRESSURE_AMPLITUDES_HPA = {3.5, 2.5, 1.5};

    private final SimulatedDevice device;
    private final List<Anomaly> anomalies;
    private final Instant start;
    private final Random random;
    private final double temperatureBase;
    private final double temperatureAmplitude;
    private final double phaseHours;
    private final double humidityBase;
    private final double humidityAmplitude;
    private final double pressureBase;
    private final double batteryStartVolts;
    private final double co2Base;
    private final double illuminancePeak;
    private final String mac;
    private final double[] pressurePhases = new double[PRESSURE_PERIODS_HOURS.length];
    private int sequence;
    private int packetId;
    private int movements;

    public ReadingModel(SimulatedDevice device, List<Anomaly> anomalies, Instant start) {
        this.device = device;
        this.anomalies = anomalies.stream().filter(a -> a.deviceId().equals(device.deviceId())).toList();
        this.start = start;
        Random seed = new Random(device.deviceId().hashCode() * 2_654_435_761L);
        this.temperatureBase = 16 + seed.nextDouble() * 8;
        this.temperatureAmplitude = 1.5 + seed.nextDouble() * 2.5;
        this.phaseHours = seed.nextDouble() * 3;
        this.humidityBase = 40 + seed.nextDouble() * 20;
        this.humidityAmplitude = 4 + seed.nextDouble() * 6;
        this.pressureBase = 1005 + seed.nextDouble() * 15;
        this.batteryStartVolts = 2.9 + seed.nextDouble() * 0.2;
        this.co2Base = 420 + seed.nextDouble() * 200;
        this.illuminancePeak = 300 + seed.nextDouble() * 500;
        byte[] macBytes = new byte[6];
        seed.nextBytes(macBytes);
        macBytes[0] = (byte) ((macBytes[0] | 0xC0) & 0xFE);
        this.mac = HexFormat.ofDelimiter(":").withUpperCase().formatHex(macBytes);
        this.sequence = seed.nextInt(1000);
        this.packetId = seed.nextInt(256);
        this.random = new Random(seed.nextLong());
        // Drawn last so that the values above (and the device's MAC) are unchanged.
        for (int i = 0; i < pressurePhases.length; i++) {
            pressurePhases[i] = seed.nextDouble() * 2 * Math.PI;
        }
    }

    public SimulatedDevice device() {
        return device;
    }

    public Reading next(Instant now) {
        double hours = now.getEpochSecond() / 3600.0;
        double daily = Math.sin(2 * Math.PI * (hours - 9 - phaseHours) / HOURS_PER_DAY);
        double daylight = Math.max(0, Math.sin(2 * Math.PI * (hours % HOURS_PER_DAY - 6) / HOURS_PER_DAY));
        double days = Duration.between(start, now).toSeconds() / 86_400.0;

        sequence = (sequence + 1) % 65_535;
        packetId = (packetId + 1) % 256;
        if (random.nextDouble() < 0.05) {
            movements = (movements + 1) % 255;
        }

        double temperature = override("temperature", now, temperatureBase + temperatureAmplitude * daily + random.nextGaussian() * 0.08);
        double humidity = override("humidity", now, clamp(humidityBase - humidityAmplitude * daily + random.nextGaussian() * 0.3, 5, 95));
        double pressure = override("pressure", now, pressureBase + pressureDrift(hours) + random.nextGaussian() * 0.05);
        double voltage = override("battery_voltage", now, clamp(batteryStartVolts - 0.002 * days + random.nextGaussian() * 0.002, 2.2, 3.6));
        double co2 = override("co2", now, Math.max(400, co2Base + 350 * daylight + random.nextGaussian() * 15));
        double illuminance = override("illuminance", now, Math.max(0, illuminancePeak * daylight + random.nextGaussian() * 5));

        Map<String, Number> m = new LinkedHashMap<>();
        Map<String, String> attributes = new LinkedHashMap<>();
        switch (device.format()) {
            case RUUVI_DF5 -> {
                m.put("temperature", temperature);
                m.put("humidity", humidity);
                m.put("pressure", pressure);
                putAcceleration(m);
                m.put("battery_voltage", clamp(voltage, 1.6, 3.646));
                m.put("tx_power", 4);
                m.put("movement_counter", movements);
                m.put("measurement_sequence", sequence);
                attributes.put("mac", mac);
            }
            case RUUVI_DF3 -> {
                m.put("temperature", temperature);
                m.put("humidity", humidity);
                m.put("pressure", pressure);
                putAcceleration(m);
                m.put("battery_voltage", voltage);
            }
            case BTHOME_V2 -> {
                m.put("packet_id", packetId);
                m.put("battery_level", (int) Math.round(clamp((voltage - 2.2) / (3.1 - 2.2) * 100, 0, 100)));
                m.put("temperature", temperature);
                m.put("humidity", humidity);
                m.put("pressure", pressure);
                m.put("co2", (int) Math.round(co2));
            }
            case CAYENNE_LPP -> {
                m.put("temperature", temperature);
                m.put("humidity", humidity);
                m.put("pressure", pressure);
                m.put("illuminance", (int) Math.round(illuminance));
            }
        }
        return new Reading(m, attributes);
    }

    private void putAcceleration(Map<String, Number> m) {
        m.put("acceleration_x", random.nextGaussian() * 0.01);
        m.put("acceleration_y", random.nextGaussian() * 0.01);
        m.put("acceleration_z", 1.0 + random.nextGaussian() * 0.005);
    }

    private double override(String metric, Instant now, double value) {
        for (Anomaly a : anomalies) {
            if (!a.metric().equals(metric) || !a.isActive(device.deviceId(), start, now)) {
                continue;
            }
            if (a.ramp().isZero()) {
                return a.value() + random.nextGaussian() * 0.05;
            }
            // Gradual and noisy, like a real change: blend the usual reading into the forced value.
            double weight = a.weight(start, now);
            double noise = random.nextGaussian() * Math.max(0.05, Math.abs(a.value()) * 0.004);
            return value + (a.value() - value) * weight + noise * weight;
        }
        return value;
    }

    private double pressureDrift(double hours) {
        double drift = 0;
        for (int i = 0; i < pressurePhases.length; i++) {
            drift += PRESSURE_AMPLITUDES_HPA[i] * Math.sin(2 * Math.PI * hours / PRESSURE_PERIODS_HOURS[i] + pressurePhases[i]);
        }
        return drift;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}

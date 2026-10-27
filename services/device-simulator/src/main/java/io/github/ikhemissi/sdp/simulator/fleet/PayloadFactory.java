package io.github.ikhemissi.sdp.simulator.fleet;

import io.github.ikhemissi.sdp.codecs.PayloadCodecs;
import java.util.Arrays;
import java.util.Random;

/**
 * Encodes readings in the device's real format with the shared codec library, and injects corrupted payloads at a
 * configurable rate (spec device-simulation: "Fault and anomaly injection"). A corrupted payload either has an
 * unknown leading byte or is truncated by one byte, which breaks the last value of every supported format.
 */
public final class PayloadFactory {

    public static final byte UNKNOWN_LEADING_BYTE = 0x7F;

    public record Payload(byte[] bytes, boolean corrupted) {
    }

    private final double corruptionRate;
    private final Random random;

    public PayloadFactory(double corruptionRate, long seed) {
        if (corruptionRate < 0 || corruptionRate > 1) {
            throw new IllegalArgumentException("corruption rate must be between 0 and 1");
        }
        this.corruptionRate = corruptionRate;
        this.random = new Random(seed);
    }

    public Payload build(SimulatedDevice device, ReadingModel.Reading reading) {
        byte[] bytes = PayloadCodecs.forFormat(device.format()).encode(reading.metrics(), reading.attributes());
        boolean corrupt;
        synchronized (random) {
            corrupt = random.nextDouble() < corruptionRate;
        }
        return corrupt ? new Payload(corrupt(bytes), true) : new Payload(bytes, false);
    }

    private byte[] corrupt(byte[] bytes) {
        boolean unknownFormat;
        synchronized (random) {
            unknownFormat = random.nextBoolean();
        }
        if (unknownFormat) {
            byte[] copy = bytes.clone();
            copy[0] = UNKNOWN_LEADING_BYTE;
            return copy;
        }
        return Arrays.copyOf(bytes, bytes.length - 1);
    }
}

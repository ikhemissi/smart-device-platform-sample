package io.github.ikhemissi.sdp.provisioning.azure;

/** IoT Hub device registry: enabling and disabling devices. */
public interface DeviceRegistry {

    enum Outcome { CHANGED, UNCHANGED, NOT_FOUND }

    /** Sets the device's status; {@link Outcome#UNCHANGED} when it already had it (e.g. disabled by someone else). */
    Outcome setEnabled(String deviceId, boolean enabled, String reason);
}

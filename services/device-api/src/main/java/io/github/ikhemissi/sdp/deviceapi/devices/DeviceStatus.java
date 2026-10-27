package io.github.ikhemissi.sdp.deviceapi.devices;

import com.fasterxml.jackson.annotation.JsonValue;

public enum DeviceStatus {
    REPORTING, SILENT;

    @JsonValue
    public String value() {
        return name().toLowerCase();
    }

    public static DeviceStatus parse(String value) {
        return value == null ? null : valueOf(value.toUpperCase());
    }
}

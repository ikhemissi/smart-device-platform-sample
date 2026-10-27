package io.github.ikhemissi.sdp.deviceapi.devices;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** @param reportingWindow a device is {@code reporting} when last seen within this window, {@code silent} otherwise */
@ConfigurationProperties("sdp.devices")
public record DevicesProperties(Duration reportingWindow) {

    public DevicesProperties {
        if (reportingWindow == null) {
            reportingWindow = Duration.ofMinutes(15);
        }
    }
}

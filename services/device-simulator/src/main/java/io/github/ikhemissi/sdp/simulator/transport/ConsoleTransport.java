package io.github.ikhemissi.sdp.simulator.transport;

import io.github.ikhemissi.sdp.fleet.SimulatedDevice;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Dry-run transport: logs each message (hex payload) instead of sending it. No Azure resource needed. */
public final class ConsoleTransport implements DeviceTransport {

    private static final Logger log = LoggerFactory.getLogger(ConsoleTransport.class);

    @Override
    public DeviceConnection connect(SimulatedDevice device) {
        return new DeviceConnection() {
            @Override
            public void send(OutgoingMessage message) {
                log.info("device={} site={} format={} id={} payload={}", device.deviceId(), device.siteId(),
                        device.format().id(), message.messageId(), HexFormat.of().withUpperCase().formatHex(message.payload()));
            }

            @Override
            public void close() {
            }
        };
    }
}

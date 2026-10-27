package io.github.ikhemissi.sdp.simulator.transport;

import io.github.ikhemissi.sdp.simulator.fleet.SimulatedDevice;

/** How simulated devices connect and send messages. */
public interface DeviceTransport {

    /** Connects a device (provisioning it first if needed). Blocking; may be retried by the caller. */
    DeviceConnection connect(SimulatedDevice device) throws Exception;

    /** One device message: binary body, unique message id and W3C trace context. */
    record OutgoingMessage(byte[] payload, String messageId, String traceparent) {
    }

    interface DeviceConnection extends AutoCloseable {

        /** Sends a message and waits for the acknowledgement. */
        void send(OutgoingMessage message) throws Exception;

        @Override
        void close();
    }
}

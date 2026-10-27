package io.github.ikhemissi.sdp.simulator.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.azure.sdk.iot.device.Message;
import io.github.ikhemissi.sdp.simulator.certs.DeviceCertificateFactory;
import java.time.Clock;
import org.junit.jupiter.api.Test;

/** Spec device-simulation: "Message identity and trace context" (IoT Hub message mapping). */
class IotHubTransportTest {

    @Test
    void messageCarriesBinaryBodyIdAndTraceContext() {
        String traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

        Message message = IotHubTransport.toIotHubMessage(
                new DeviceTransport.OutgoingMessage(new byte[] {(byte) 0xCA, 1, 0x67, 0, (byte) 0xE1}, "msg-1", traceparent));

        assertThat(message.getBytes()).containsExactly(0xCA, 1, 0x67, 0, 0xE1);
        assertThat(message.getMessageId()).isEqualTo("msg-1");
        assertThat(message.getContentType()).isEqualTo("application/octet-stream");
        assertThat(message.getProperty("traceparent")).isEqualTo(traceparent);
    }

    @Test
    void idScopeIsRequired() {
        assertThatThrownBy(() -> new IotHubTransport("global.azure-devices-provisioning.net", "", siteId -> null,
                new DeviceCertificateFactory(), Clock.systemUTC())).hasMessageContaining("id-scope");
    }
}

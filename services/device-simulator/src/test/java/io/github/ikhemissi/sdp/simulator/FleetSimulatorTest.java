package io.github.ikhemissi.sdp.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.ikhemissi.sdp.codecs.PayloadFormat;
import io.github.ikhemissi.sdp.fleet.PayloadFactory;
import io.github.ikhemissi.sdp.fleet.SimulatedDevice;
import io.github.ikhemissi.sdp.simulator.transport.DeviceTransport;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Spec device-simulation: "Message identity and trace context", "Execution modes" (graceful stop), fleet run
 * duration and connection retries.
 */
class FleetSimulatorTest {

    record Sent(String deviceId, DeviceTransport.OutgoingMessage message) {
    }

    /** In-memory transport recording messages; optionally slow or failing to connect. */
    static final class RecordingTransport implements DeviceTransport {
        final List<Sent> sent = new CopyOnWriteArrayList<>();
        final Set<String> open = ConcurrentHashMap.newKeySet();
        final Map<String, AtomicInteger> connectFailures = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> connections = new ConcurrentHashMap<>();
        /** Device whose first connection is lost (client gave up) after this many messages. */
        final Map<String, Integer> lostAfter = new ConcurrentHashMap<>();
        volatile Duration sendLatency = Duration.ZERO;

        @Override
        public DeviceConnection connect(SimulatedDevice device) {
            AtomicInteger failures = connectFailures.get(device.deviceId());
            if (failures != null && failures.getAndDecrement() > 0) {
                throw new IllegalStateException("DPS unavailable");
            }
            open.add(device.deviceId());
            int connection = connections.computeIfAbsent(device.deviceId(), id -> new AtomicInteger()).incrementAndGet();
            AtomicInteger sentOnConnection = new AtomicInteger();
            return new DeviceConnection() {
                @Override
                public void send(OutgoingMessage message) throws InterruptedException {
                    Thread.sleep(sendLatency);
                    sent.add(new Sent(device.deviceId(), message));
                    sentOnConnection.incrementAndGet();
                }

                @Override
                public boolean lost() {
                    Integer limit = lostAfter.get(device.deviceId());
                    return connection == 1 && limit != null && sentOnConnection.get() >= limit;
                }

                @Override
                public void close() {
                    open.remove(device.deviceId());
                }
            };
        }
    }

    private final InMemorySpanExporter spans = InMemorySpanExporter.create();
    private final OpenTelemetrySdk otel = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build()).build();
    private final RecordingTransport transport = new RecordingTransport();
    private FleetSimulator simulator;

    @AfterEach
    void tearDown() {
        if (simulator != null) {
            simulator.stop();
        }
        otel.close();
    }

    private FleetSimulator simulator(Duration interval, Duration duration) {
        SimulatorProperties properties = new SimulatorProperties(List.of("paris-01", "lyon-01"), 2,
                List.of("ruuvi-df5", "bthome-v2"), interval, Duration.ofMillis(20), duration, 0, List.of(), "console",
                new SimulatorProperties.Dps("global.azure-devices-provisioning.net", null), new SimulatorProperties.Ca(null, (Path) null),
                List.of());
        List<SimulatedDevice> fleet = SimulatedDevice.plan(properties.sites(), 2, List.of(PayloadFormat.RUUVI_DF5, PayloadFormat.BTHOME_V2));
        simulator = new FleetSimulator(fleet, List.of(), properties, transport, new PayloadFactory(0, 1), Clock.systemUTC(), otel);
        return simulator;
    }

    @Test
    void everyDeviceSendsMessagesWithUniqueIdsAndTraceContext() {
        simulator(Duration.ofMillis(150), null).start();

        await().atMost(Duration.ofSeconds(10)).until(() ->
                transport.sent.stream().collect(Collectors.groupingBy(Sent::deviceId, Collectors.counting())).values().stream()
                        .filter(n -> n >= 3).count() == 4);

        assertThat(transport.sent.stream().map(s -> s.message().messageId())).doesNotHaveDuplicates().allMatch(id -> !id.isBlank());
        Sent first = transport.sent.getFirst();
        assertThat(first.message().traceparent()).matches("^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");
        String traceId = first.message().traceparent().substring(3, 35);
        SpanData span = spans.getFinishedSpanItems().stream().filter(s -> s.getTraceId().equals(traceId)).findFirst().orElseThrow();
        assertThat(span.getName()).isEqualTo("sdp.device.send");
        assertThat(span.getKind()).isEqualTo(SpanKind.PRODUCER);
        assertThat(span.getAttributes().asMap().toString()).contains(first.deviceId());
    }

    @Test
    void gracefulStopClosesAllConnectionsWithinTenSeconds() throws Exception {
        transport.sendLatency = Duration.ofSeconds(30);
        FleetSimulator sim = simulator(Duration.ofMillis(100), null);
        sim.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> transport.open.size() == 4);

        long started = System.nanoTime();
        sim.stop();
        sim.awaitTermination();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
        assertThat(transport.open).isEmpty();
        assertThat(sim.isRunning()).isFalse();
    }

    @Test
    void stopsByItselfAfterTheConfiguredDuration() throws Exception {
        FleetSimulator sim = simulator(Duration.ofMillis(100), Duration.ofSeconds(1));
        long started = System.nanoTime();
        sim.start();

        sim.awaitTermination();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isBetween(Duration.ofMillis(900), Duration.ofSeconds(5));
        assertThat(transport.open).isEmpty();
    }

    @Test
    void connectionFailuresAreRetried() {
        transport.connectFailures.put("paris-01-ruuvi5-001", new AtomicInteger(1));
        simulator(Duration.ofMillis(100), null).start();

        await().atMost(Duration.ofSeconds(15)).until(() ->
                transport.sent.stream().anyMatch(s -> s.deviceId().equals("paris-01-ruuvi5-001")));
    }

    @Test
    void aLostConnectionIsReplacedByANewOne() {
        transport.lostAfter.put("paris-01-ruuvi5-001", 2);
        simulator(Duration.ofMillis(100), null).start();

        await().atMost(Duration.ofSeconds(15)).until(() ->
                transport.sent.stream().filter(s -> s.deviceId().equals("paris-01-ruuvi5-001")).count() >= 4);

        assertThat(transport.connections.get("paris-01-ruuvi5-001")).hasValue(2);
        assertThat(transport.connections.get("lyon-01-ruuvi5-001")).hasValue(1);
    }
}

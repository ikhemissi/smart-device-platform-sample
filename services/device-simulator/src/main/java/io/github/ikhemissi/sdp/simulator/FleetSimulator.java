package io.github.ikhemissi.sdp.simulator;

import io.github.ikhemissi.sdp.simulator.fleet.Anomaly;
import io.github.ikhemissi.sdp.simulator.fleet.PayloadFactory;
import io.github.ikhemissi.sdp.simulator.fleet.ReadingModel;
import io.github.ikhemissi.sdp.simulator.fleet.SimulatedDevice;
import io.github.ikhemissi.sdp.simulator.transport.DeviceTransport;
import io.github.ikhemissi.sdp.simulator.transport.DeviceTransport.DeviceConnection;
import io.github.ikhemissi.sdp.simulator.transport.DeviceTransport.OutgoingMessage;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Runs the simulated fleet: one virtual thread per device that connects (with retries), then sends a reading every
 * interval ± jitter until stopped or until the configured duration elapses. Each send is a PRODUCER span whose
 * context is propagated in the message's {@code traceparent} (spec device-simulation, platform-observability).
 */
public class FleetSimulator implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(FleetSimulator.class);
    static final Duration STOP_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration MAX_CONNECT_BACKOFF = Duration.ofSeconds(60);
    private static final AttributeKey<String> DEVICE_ID = AttributeKey.stringKey("sdp.device.id");
    private static final AttributeKey<String> SITE_ID = AttributeKey.stringKey("sdp.site.id");
    private static final AttributeKey<String> FORMAT = AttributeKey.stringKey("sdp.payload.format");
    private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("sdp.simulator.outcome");

    private final List<SimulatedDevice> fleet;
    private final List<Anomaly> anomalies;
    private final SimulatorProperties properties;
    private final DeviceTransport transport;
    private final PayloadFactory payloads;
    private final Clock clock;
    private final Tracer tracer;
    private final LongCounter messages;
    private final AtomicBoolean running = new AtomicBoolean();
    private final CountDownLatch terminated = new CountDownLatch(1);
    private ExecutorService devices;
    private Thread durationWatcher;

    public FleetSimulator(List<SimulatedDevice> fleet, List<Anomaly> anomalies, SimulatorProperties properties,
                          DeviceTransport transport, PayloadFactory payloads, Clock clock, OpenTelemetry openTelemetry) {
        this.fleet = fleet;
        this.anomalies = anomalies;
        this.properties = properties;
        this.transport = transport;
        this.payloads = payloads;
        this.clock = clock;
        this.tracer = openTelemetry.getTracer("sdp-device-simulator");
        this.messages = openTelemetry.getMeter("sdp-device-simulator").counterBuilder("sdp.simulator.messages")
                .setDescription("Messages sent by simulated devices").setUnit("{message}").build();
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Instant start = clock.instant();
        log.info("Starting simulated fleet: {} devices, sites={}, formats={}, interval={}±{}, corruptionRate={}, anomalies={}, duration={}",
                fleet.size(), properties.sites(), properties.formats(), properties.interval(), properties.jitter(),
                properties.corruptionRate(), anomalies, properties.duration() == null ? "unlimited" : properties.duration());
        devices = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("device-", 0).factory());
        for (SimulatedDevice device : fleet) {
            devices.submit(() -> runDevice(device, start));
        }
        if (properties.duration() != null) {
            durationWatcher = Thread.ofVirtual().name("simulation-duration").start(() -> {
                try {
                    Thread.sleep(properties.duration());
                    log.info("Simulation duration {} elapsed", properties.duration());
                    stop();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
    }

    private void runDevice(SimulatedDevice device, Instant start) {
        ReadingModel model = new ReadingModel(device, anomalies, start);
        DeviceConnection connection = connectWithRetry(device);
        if (connection == null) {
            return;
        }
        try (connection) {
            // Spread the first messages over one interval so the fleet does not send in bursts.
            sleep(Duration.ofMillis(ThreadLocalRandom.current().nextLong(properties.interval().toMillis())));
            while (running.get()) {
                send(device, model, connection);
                long jitter = properties.jitter().toMillis();
                long delay = properties.interval().toMillis() + (jitter == 0 ? 0 : ThreadLocalRandom.current().nextLong(-jitter, jitter + 1));
                sleep(Duration.ofMillis(delay));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void send(SimulatedDevice device, ReadingModel model, DeviceConnection connection) throws InterruptedException {
        ReadingModel.Reading reading = model.next(clock.instant());
        PayloadFactory.Payload payload = payloads.build(device, reading);
        Span span = tracer.spanBuilder("sdp.device.send").setSpanKind(SpanKind.PRODUCER)
                .setAttribute(DEVICE_ID, device.deviceId())
                .setAttribute(SITE_ID, device.siteId())
                .setAttribute(FORMAT, device.format().id())
                .startSpan();
        Map<String, String> carrier = new HashMap<>();
        W3CTraceContextPropagator.getInstance().inject(Context.current().with(span), carrier, Map::put);
        String outcome = payload.corrupted() ? "corrupted" : "sent";
        try {
            connection.send(new OutgoingMessage(payload.bytes(), UUID.randomUUID().toString(), carrier.get("traceparent")));
        } catch (InterruptedException e) {
            outcome = "interrupted";
            throw e;
        } catch (Exception e) {
            outcome = "failed";
            span.recordException(e);
            span.setStatus(StatusCode.ERROR);
            log.warn("Device {} failed to send: {}", device.deviceId(), e.toString());
        } finally {
            span.setAttribute(OUTCOME, outcome);
            span.end();
            messages.add(1, Attributes.of(FORMAT, device.format().id(), OUTCOME, outcome));
        }
    }

    private DeviceConnection connectWithRetry(SimulatedDevice device) {
        Duration backoff = Duration.ofSeconds(2);
        while (running.get()) {
            try {
                return transport.connect(device);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                log.warn("Device {} cannot connect ({}), retrying in {}", device.deviceId(), e.toString(), backoff);
                try {
                    sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                backoff = backoff.multipliedBy(2).compareTo(MAX_CONNECT_BACKOFF) > 0 ? MAX_CONNECT_BACKOFF : backoff.multipliedBy(2);
            }
        }
        return null;
    }

    private void sleep(Duration duration) throws InterruptedException {
        if (!duration.isNegative() && !duration.isZero()) {
            Thread.sleep(duration);
        }
    }

    /** Stops all devices: in-flight sends complete or are abandoned and connections are closed (spec: within 10 s). */
    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        log.info("Stopping simulated fleet");
        if (durationWatcher != null && Thread.currentThread() != durationWatcher) {
            durationWatcher.interrupt();
        }
        devices.shutdownNow();
        try {
            if (!devices.awaitTermination(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("Some devices did not stop within {}", STOP_TIMEOUT);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        terminated.countDown();
        log.info("Simulated fleet stopped");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /** Blocks until the simulation stops (duration elapsed or application shutdown). */
    public void awaitTermination() throws InterruptedException {
        terminated.await();
    }

    public List<SimulatedDevice> fleet() {
        return List.copyOf(fleet);
    }
}

package io.github.ikhemissi.sdp.provisioning.sites;

import io.github.ikhemissi.sdp.provisioning.azure.DeviceRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Disables all devices of a site in IoT Hub when it loses its last certificate, and re-enables only those devices when
 * trust is restored (M4 design D3). Each device's flag is committed as soon as IoT Hub is updated, so a retried
 * operation picks up where it stopped. Failed operations stay pending and are retried by reconciliation (5 attempts).
 */
@Component
public class DeviceOperations {

    static final int MAX_ATTEMPTS = 5;
    private static final Logger log = LoggerFactory.getLogger(DeviceOperations.class);

    private final SiteRepository repository;
    private final DeviceRegistry registry;
    private final SiteLocks locks;

    public DeviceOperations(SiteRepository repository, DeviceRegistry registry, SiteLocks locks) {
        this.repository = repository;
        this.registry = registry;
        this.locks = locks;
    }

    /** Runs a pending operation; returns its updated state. */
    public DeviceOperation run(UUID operationId) {
        return locks.withSite(repository.operation(operationId).orElseThrow().siteId(), () -> {
            DeviceOperation op = repository.operation(operationId).orElseThrow();
            if (!DeviceOperation.PENDING.equals(op.status())) {
                return op;
            }
            try {
                int changed = DeviceOperation.DISABLE.equals(op.kind()) ? disable(op.siteId()) : enable(op.siteId());
                repository.finishOperation(op.id(), DeviceOperation.DONE, null);
                repository.audit(op.siteId(), "platform", "Smart Device Platform",
                        DeviceOperation.DISABLE.equals(op.kind()) ? AuditEvent.DEVICES_DISABLED : AuditEvent.DEVICES_ENABLED,
                        null, Map.of("devices", changed, "operationId", op.id().toString()));
                log.info("{} on site {}: {} device(s) changed", op.kind(), op.siteId(), changed);
            } catch (RuntimeException e) {
                boolean giveUp = op.attempts() + 1 >= MAX_ATTEMPTS;
                repository.finishOperation(op.id(), giveUp ? DeviceOperation.FAILED : DeviceOperation.PENDING, e.getMessage());
                log.warn("{} on site {} failed (attempt {}): {}", op.kind(), op.siteId(), op.attempts() + 1, e.toString());
            }
            return repository.operation(operationId).orElseThrow();
        });
    }

    private int disable(String siteId) {
        int changed = 0;
        for (String deviceId : repository.deviceIds(siteId)) {
            if (registry.setEnabled(deviceId, false, "Site " + siteId + " has no trusted certificate") == DeviceRegistry.Outcome.CHANGED) {
                repository.markDisabledByPlatform(deviceId, true);
                changed++;
            }
        }
        return changed;
    }

    private int enable(String siteId) {
        List<String> devices = repository.devicesDisabledByPlatform(siteId);
        int changed = 0;
        for (String deviceId : devices) {
            if (registry.setEnabled(deviceId, true, "") == DeviceRegistry.Outcome.CHANGED) {
                changed++;
            }
            repository.markDisabledByPlatform(deviceId, false);
        }
        return changed;
    }
}

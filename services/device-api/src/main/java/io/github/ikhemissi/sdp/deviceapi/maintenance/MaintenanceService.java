package io.github.ikhemissi.sdp.deviceapi.maintenance;

import io.github.ikhemissi.sdp.deviceapi.web.ApiException;
import io.github.ikhemissi.sdp.platform.security.SdpUser;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Maintenance queue, device assessments and Editors' statuses (spec predictive-maintenance, M9 design D8). */
@Service
public class MaintenanceService {

    static final List<String> STATUSES = List.of("open", "planned", "done", "resolved");
    /** The queue: items to act on. */
    static final List<String> DEFAULT_STATUSES = List.of("open", "planned");

    private final MaintenanceRepository repository;
    private final MaintenanceProperties properties;
    private final Clock clock;

    public MaintenanceService(MaintenanceRepository repository, MaintenanceProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /** Items of the assessed sites; none when maintenance is off. */
    @Transactional(readOnly = true)
    public List<MaintenanceItem> items(String siteId, List<String> statuses, int limit) {
        List<String> requested = statuses(statuses);
        if (!properties.enabled() || (siteId != null && !properties.assesses(siteId))) {
            return List.of();
        }
        return repository.items(siteId, properties.sites(), requested, limit);
    }

    @Transactional(readOnly = true)
    public DeviceMaintenance device(String deviceId) {
        String siteId = repository.deviceSite(deviceId)
                .orElseThrow(() -> ApiException.notFound("Device '" + deviceId + "' does not exist."));
        if (!properties.assesses(siteId)) {
            return new DeviceMaintenance(deviceId, false, null, null);
        }
        return new DeviceMaintenance(deviceId, true, repository.assessment(deviceId).orElse(null),
                repository.queued(deviceId).orElse(null));
    }

    /** An Editor marks an item of the queue planned or done; closed items cannot change. */
    @Transactional
    public MaintenanceItem setStatus(long id, StatusInput input, SdpUser user) {
        MaintenanceItem item = repository.item(id)
                .filter(i -> properties.assesses(i.siteId()))
                .orElseThrow(() -> ApiException.notFound("Maintenance item " + id + " does not exist."));
        String by = user.displayName() != null ? user.displayName() : user.userPrincipalName();
        if (!DEFAULT_STATUSES.contains(item.status())
                || !repository.setStatus(id, input.status(), input.normalizedComment(), by,
                        clock.instant().truncatedTo(ChronoUnit.MICROS))) {
            throw new ApiException(HttpStatus.CONFLICT, "Maintenance item " + id + " is closed (" + item.status()
                    + "); its status can no longer change.");
        }
        return repository.item(id).orElseThrow();
    }

    /** Requested statuses (repeated or comma-separated), or the queue's. */
    private static List<String> statuses(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return DEFAULT_STATUSES;
        }
        List<String> statuses = requested.stream()
                .flatMap(s -> Arrays.stream(s.split(",")))
                .map(s -> s.strip().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .distinct().toList();
        List<String> unknown = statuses.stream().filter(s -> !STATUSES.contains(s)).toList();
        if (!unknown.isEmpty()) {
            throw ApiException.invalid(Map.of("status", List.of("must be among " + String.join(", ", STATUSES)
                    + " (got " + String.join(", ", unknown) + ")")));
        }
        return statuses.isEmpty() ? DEFAULT_STATUSES : statuses;
    }
}

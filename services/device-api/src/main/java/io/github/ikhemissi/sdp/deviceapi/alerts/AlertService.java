package io.github.ikhemissi.sdp.deviceapi.alerts;

import io.github.ikhemissi.sdp.deviceapi.web.ApiException;
import io.github.ikhemissi.sdp.deviceapi.web.EntityTags;
import io.github.ikhemissi.sdp.deviceapi.web.Period;
import io.github.ikhemissi.sdp.platform.security.SdpUser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Threshold management and alert history (spec threshold-alerting). A threshold change locks the threshold's
 * evaluation state, so it is serialised with the alert service's evaluation; disabling, deleting or moving a threshold
 * to another metric closes its open alert without notification (M5 design D4).
 */
@Service
public class AlertService {

    static final Duration DEFAULT_PERIOD = Duration.ofDays(30);
    static final Duration MAX_PERIOD = Duration.ofDays(92);

    private final AlertRepository repository;
    private final Clock clock;

    public AlertService(AlertRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Threshold> thresholds(String deviceId) {
        requireDevice(deviceId);
        return repository.thresholds(deviceId);
    }

    @Transactional
    public Threshold create(String deviceId, ThresholdInput input, SdpUser user) {
        requireDevice(deviceId);
        validate(deviceId, input);
        if (repository.countThresholds(deviceId) >= ThresholdInput.MAX_PER_DEVICE) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "A device has at most " + ThresholdInput.MAX_PER_DEVICE + " thresholds; delete one before adding another.");
        }
        UUID id = UUID.randomUUID();
        repository.insertThreshold(id, deviceId, input, now(), user.objectId(), user.displayName());
        return repository.threshold(deviceId, id).orElseThrow();
    }

    @Transactional
    public Threshold replace(String deviceId, UUID id, String ifMatch, ThresholdInput input, SdpUser user) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ApiException(HttpStatus.PRECONDITION_REQUIRED,
                    "Send the threshold's current entity tag in If-Match to avoid overwriting a concurrent change.");
        }
        int version = repository.lockThreshold(deviceId, id).orElseThrow(() -> notFound(deviceId, id));
        Threshold current = repository.threshold(deviceId, id).orElseThrow();
        if (!EntityTags.matches(ifMatch, Threshold.entityTag(id, version))) {
            throw new ApiException(HttpStatus.PRECONDITION_FAILED,
                    "The threshold was changed by someone else; reload it and apply your change again.");
        }
        validate(deviceId, input);
        Instant now = now();
        if (!input.enabledOrDefault() || !current.metric().equals(input.metric().strip())) {
            repository.cancelOpenAlert(id, now);
        }
        repository.updateThreshold(id, input, now, user.objectId(), user.displayName());
        return repository.threshold(deviceId, id).orElseThrow();
    }

    @Transactional
    public void delete(String deviceId, UUID id, String ifMatch) {
        int version = repository.lockThreshold(deviceId, id).orElseThrow(() -> notFound(deviceId, id));
        if (ifMatch != null && !ifMatch.isBlank() && !EntityTags.matches(ifMatch, Threshold.entityTag(id, version))) {
            throw new ApiException(HttpStatus.PRECONDITION_FAILED,
                    "The threshold was changed by someone else; reload it before deleting it.");
        }
        repository.cancelOpenAlert(id, now());
        repository.deleteThreshold(id);
    }

    @Transactional(readOnly = true)
    public List<Alert> deviceAlerts(String deviceId, Instant from, Instant to, int limit) {
        requireDevice(deviceId);
        Period period = period(from, to);
        return repository.alerts(deviceId, null, null, period.from(), period.to(), limit);
    }

    @Transactional(readOnly = true)
    public List<Alert> alerts(String status, String siteId, Instant from, Instant to, int limit) {
        Period period = period(from, to);
        return repository.alerts(null, siteId, status == null ? null : status.toUpperCase(), period.from(), period.to(), limit);
    }

    private void validate(String deviceId, ThresholdInput input) {
        Map<String, List<String>> errors = input.violations();
        if (!errors.isEmpty()) {
            throw ApiException.invalid(errors);
        }
        if (!repository.reportsMetric(deviceId, input.metric().strip())) {
            throw ApiException.invalid(Map.of("metric", List.of("'" + input.metric() + "' is not reported by device " + deviceId)));
        }
    }

    private Period period(Instant from, Instant to) {
        return Period.of(from, to, now(), DEFAULT_PERIOD, MAX_PERIOD);
    }

    private void requireDevice(String deviceId) {
        if (!repository.deviceExists(deviceId)) {
            throw ApiException.notFound("Device '" + deviceId + "' does not exist.");
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static ApiException notFound(String deviceId, UUID id) {
        return ApiException.notFound("Device '" + deviceId + "' has no threshold " + id + ".");
    }
}

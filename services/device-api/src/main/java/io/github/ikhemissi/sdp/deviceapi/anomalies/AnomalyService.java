package io.github.ikhemissi.sdp.deviceapi.anomalies;

import io.github.ikhemissi.sdp.deviceapi.web.ApiException;
import io.github.ikhemissi.sdp.platform.security.SdpUser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Anomaly listing and reviews (spec anomaly-detection, M7 design D7). */
@Service
public class AnomalyService {

    static final Duration DEFAULT_PERIOD = Duration.ofDays(7);
    static final Duration MAX_PERIOD = Duration.ofDays(92);
    static final List<String> STATUSES = List.of("confirmed", "to_review", "dismissed", "reviewed", "pending");
    /** Anomalies to act on (list) and a device's history (device page), dismissed and pending ones on request only. */
    static final List<String> DEFAULT_STATUSES = List.of("confirmed", "to_review");
    static final List<String> DEFAULT_DEVICE_STATUSES = List.of("confirmed", "to_review", "reviewed");

    private final AnomalyRepository repository;
    private final Clock clock;

    public AnomalyService(AnomalyRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Anomaly> anomalies(String siteId, String deviceId, List<String> statuses, Instant from, Instant to, int limit) {
        Period period = period(from, to);
        return repository.anomalies(deviceId, siteId, statuses(statuses, DEFAULT_STATUSES), period.from(), period.to(), limit);
    }

    @Transactional(readOnly = true)
    public List<Anomaly> deviceAnomalies(String deviceId, List<String> statuses, Instant from, Instant to, int limit) {
        if (!repository.deviceExists(deviceId)) {
            throw ApiException.notFound("Device '" + deviceId + "' does not exist.");
        }
        Period period = period(from, to);
        return repository.anomalies(deviceId, null, statuses(statuses, DEFAULT_DEVICE_STATUSES), period.from(), period.to(),
                limit);
    }

    /** Records an Editor's verdict; a new review replaces the previous one. */
    @Transactional
    public Anomaly review(long id, ReviewInput input, SdpUser user) {
        Anomaly anomaly = repository.anomaly(id).orElseThrow(() -> ApiException.notFound("Anomaly " + id + " does not exist."));
        if ("pending".equals(anomaly.decision())) {
            throw new ApiException(HttpStatus.CONFLICT, "Anomaly " + id + " is not classified yet; review it once it is.");
        }
        String reviewer = user.displayName() != null ? user.displayName() : user.userPrincipalName();
        repository.review(id, input.verdict(), input.normalizedComment(), reviewer, clock.instant().truncatedTo(ChronoUnit.MICROS));
        return repository.anomaly(id).orElseThrow();
    }

    /** Requested statuses (repeated or comma-separated), or the defaults. */
    private static List<String> statuses(List<String> requested, List<String> defaults) {
        if (requested == null || requested.isEmpty()) {
            return defaults;
        }
        List<String> statuses = requested.stream()
                .flatMap(s -> Arrays.stream(s.split(",")))
                .map(s -> s.strip().toLowerCase(Locale.ROOT).replace(' ', '_'))
                .filter(s -> !s.isEmpty())
                .distinct().toList();
        List<String> unknown = statuses.stream().filter(s -> !STATUSES.contains(s)).toList();
        if (!unknown.isEmpty()) {
            throw ApiException.invalid(Map.of("status", List.of("must be among " + String.join(", ", STATUSES)
                    + " (got " + String.join(", ", unknown) + ")")));
        }
        return statuses.isEmpty() ? defaults : statuses;
    }

    private Period period(Instant from, Instant to) {
        Instant end = to != null ? to : clock.instant().plusSeconds(1);
        Instant start = from != null ? from : end.minus(DEFAULT_PERIOD);
        if (!start.isBefore(end)) {
            throw ApiException.invalid(Map.of("from", List.of("must be before to")));
        }
        if (Duration.between(start, end).compareTo(MAX_PERIOD) > 0) {
            throw ApiException.invalid(Map.of("from", List.of("the period is at most 92 days")));
        }
        return new Period(start, end);
    }

    private record Period(Instant from, Instant to) {
    }
}

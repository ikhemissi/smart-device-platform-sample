package io.github.ikhemissi.sdp.deviceapi.fleet;

import io.github.ikhemissi.sdp.deviceapi.devices.DevicesProperties;
import io.github.ikhemissi.sdp.deviceapi.fleet.FleetRepository.Partial;
import io.github.ikhemissi.sdp.deviceapi.fleet.MetricSummary.GroupBy;
import io.github.ikhemissi.sdp.deviceapi.history.HistoryProperties;
import io.github.ikhemissi.sdp.deviceapi.web.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/**
 * Fleet summaries (spec fleet-analytics). Complete hours come from the hourly rollups maintained by the database
 * maintenance job; the range edges and the hours not rolled up yet are aggregated from raw measurements.
 */
@Service
public class FleetService {

    private final FleetRepository repository;
    private final HistoryProperties history;
    private final DevicesProperties devices;
    private final Clock clock;

    public FleetService(FleetRepository repository, HistoryProperties history, DevicesProperties devices, Clock clock) {
        this.repository = repository;
        this.history = history;
        this.devices = devices;
        this.clock = clock;
    }

    public MetricSummary metricSummary(String metric, Instant from, Instant to, GroupBy groupBy, String siteId) {
        String unit = repository.unit(metric).orElseThrow(() -> ApiException.invalid(Map.of("metric",
                List.of("must be one of " + String.join(", ", repository.metricNames())))));
        if (!from.isBefore(to)) {
            throw ApiException.invalid(Map.of("from", List.of("must be before to")));
        }
        if (Duration.between(from, to).compareTo(history.maxRange()) > 0) {
            throw ApiException.invalid(Map.of("to", List.of("the range must not exceed " + history.maxRange().toDays() + " days")));
        }
        requireSite(siteId);

        Instant firstHour = ceilHour(from);
        Instant rolledTo = repository.rolledUntil().map(r -> min(r, to.truncatedTo(ChronoUnit.HOURS))).orElse(firstHour);
        List<Partial> partials = rolledTo.isAfter(firstHour)
                ? repository.partials(metric, siteId, from, firstHour, firstHour, rolledTo, rolledTo, to)
                : repository.partials(metric, siteId, from, to, from, from, to, to);

        Map<String, Aggregate> byDevice = new LinkedHashMap<>();
        partials.forEach(p -> byDevice.computeIfAbsent(p.deviceId(), id -> new Aggregate(p.siteId(), id)).add(p));
        Map<String, Aggregate> groups = new TreeMap<>();
        for (Aggregate device : byDevice.values()) {
            if (groupBy == GroupBy.device) {
                groups.put(device.siteId + "/" + device.deviceId, device);
            } else {
                groups.computeIfAbsent(device.siteId, id -> new Aggregate(id, null)).merge(device);
            }
        }
        return new MetricSummary(metric, unit, from, to, groupBy, siteId,
                groups.values().stream().map(Aggregate::toGroup).toList());
    }

    public List<SiteStatus> status(String siteId) {
        requireSite(siteId);
        return repository.status(siteId, clock.instant().minus(devices.reportingWindow()));
    }

    private void requireSite(String siteId) {
        if (siteId != null && !repository.siteExists(siteId)) {
            throw ApiException.notFound("Site '" + siteId + "' does not exist.");
        }
    }

    private static Instant ceilHour(Instant at) {
        Instant floor = at.truncatedTo(ChronoUnit.HOURS);
        return floor.equals(at) ? at : floor.plus(Duration.ofHours(1));
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    /** Running min/avg/max/count and first/last reading of a device or a group of devices. */
    private static final class Aggregate {
        private final String siteId;
        private final String deviceId;
        private final Set<String> deviceIds = new HashSet<>();
        private double min = Double.POSITIVE_INFINITY;
        private double max = Double.NEGATIVE_INFINITY;
        private double sum;
        private long count;
        private MetricSummary.Reading first;
        private MetricSummary.Reading last;

        Aggregate(String siteId, String deviceId) {
            this.siteId = siteId;
            this.deviceId = deviceId;
        }

        void add(Partial p) {
            deviceIds.add(p.deviceId());
            combine(p.min(), p.max(), p.sum(), p.count(), new MetricSummary.Reading(p.firstAt(), p.firstValue()),
                    new MetricSummary.Reading(p.lastAt(), p.lastValue()));
        }

        void merge(Aggregate other) {
            deviceIds.addAll(other.deviceIds);
            combine(other.min, other.max, other.sum, other.count, other.first, other.last);
        }

        private void combine(double otherMin, double otherMax, double otherSum, long otherCount,
                             MetricSummary.Reading otherFirst, MetricSummary.Reading otherLast) {
            min = Math.min(min, otherMin);
            max = Math.max(max, otherMax);
            sum += otherSum;
            count += otherCount;
            first = first == null || otherFirst.at().isBefore(first.at()) ? otherFirst : first;
            last = last == null || otherLast.at().isAfter(last.at()) ? otherLast : last;
        }

        MetricSummary.Group toGroup() {
            return new MetricSummary.Group(siteId, deviceId, min, sum / count, max, count, deviceIds.size(), first, last);
        }
    }

}

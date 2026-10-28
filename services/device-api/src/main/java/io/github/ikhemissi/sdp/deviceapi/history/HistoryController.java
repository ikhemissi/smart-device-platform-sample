package io.github.ikhemissi.sdp.deviceapi.history;

import io.github.ikhemissi.sdp.deviceapi.web.ApiException;
import io.github.ikhemissi.sdp.platform.security.RequiredRole;
import io.github.ikhemissi.sdp.platform.security.SdpRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/devices/{deviceId}")
@RequiredRole(SdpRole.READ)
@Tag(name = "Device history")
public class HistoryController {

    private static final Pattern METRIC = Pattern.compile("[a-z0-9_]{1,64}");

    private final HistoryService history;
    private final Clock clock;

    public HistoryController(HistoryService history, Clock clock) {
        this.history = history;
        this.clock = clock;
    }

    @GetMapping("/measurements/latest")
    @Operation(operationId = "listLatestMeasurements", summary = "Latest measurements of a device, most recent first")
    public List<Measurement> latest(@PathVariable String deviceId,
                                    @RequestParam(defaultValue = "20") @Min(value = 1, message = "must be at least 1")
                                    @Max(value = 100, message = "must be at most 100") int count) {
        return history.latest(deviceId, count);
    }

    @GetMapping("/measurements")
    @Operation(operationId = "listMeasurements", summary = "Measurements of a device in a time range",
            description = "from (inclusive) to to (exclusive), at most 31 days, ascending time order, paged with an opaque cursor.")
    public MeasurementPage range(@PathVariable String deviceId,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                 @RequestParam(required = false) String cursor,
                                 @RequestParam(defaultValue = "500") @Min(value = 1, message = "must be at least 1")
                                 @Max(value = 1000, message = "must be at most 1000") int pageSize) {
        return history.range(deviceId, from, to, cursor, pageSize);
    }

    @GetMapping("/series")
    @Operation(operationId = "getSeries", summary = "Chart series of 1 to 4 metrics",
            description = "Raw points when the range holds at most maxPoints measurements, otherwise min/avg/max/count "
                    + "buckets of the smallest of 1m, 5m, 15m, 1h, 6h, 1d giving at most maxPoints buckets. "
                    + "to defaults to now; the range is at most 31 days.")
    public Series series(@PathVariable String deviceId,
                         @RequestParam @Parameter(description = "Comma-separated metric keys, e.g. temperature,humidity") String metrics,
                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                         @RequestParam(defaultValue = "500") @Min(value = 1, message = "must be at least 1")
                         @Max(value = 2000, message = "must be at most 2000") int maxPoints) {
        return history.series(deviceId, parseMetrics(metrics), from, to == null ? clock.instant() : to, maxPoints);
    }

    @GetMapping("/metrics")
    @Operation(operationId = "listDeviceMetrics", summary = "Metrics the device reported within the retention period")
    public List<ReportedMetric> metrics(@PathVariable String deviceId) {
        return history.metrics(deviceId);
    }

    static List<String> parseMetrics(String metrics) {
        List<String> parsed = List.copyOf(new LinkedHashSet<>(Arrays.stream(metrics.split(","))
                .map(String::strip).filter(m -> !m.isEmpty()).toList()));
        if (parsed.isEmpty() || parsed.size() > 4) {
            throw ApiException.invalid(Map.of("metrics", List.of("must list 1 to 4 metrics")));
        }
        if (!parsed.stream().allMatch(m -> METRIC.matcher(m).matches())) {
            throw ApiException.invalid(Map.of("metrics", List.of("must be metric keys such as temperature")));
        }
        return parsed;
    }
}

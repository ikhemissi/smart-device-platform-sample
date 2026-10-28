package io.github.ikhemissi.sdp.deviceapi.fleet;

import io.github.ikhemissi.sdp.deviceapi.fleet.MetricSummary.GroupBy;
import io.github.ikhemissi.sdp.deviceapi.web.ApiException;
import io.github.ikhemissi.sdp.platform.security.RequiredRole;
import io.github.ikhemissi.sdp.platform.security.SdpRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/fleet")
@RequiredRole(SdpRole.READ)
@Tag(name = "Fleet")
public class FleetController {

    private final FleetService fleet;
    private final Clock clock;

    public FleetController(FleetService fleet, Clock clock) {
        this.fleet = fleet;
        this.clock = clock;
    }

    @GetMapping("/metric-summary")
    @Operation(operationId = "getFleetMetricSummary", summary = "Summary of one metric per site or per device",
            description = "min, avg, max, sample and device counts, first and last values of a canonical metric from from "
                    + "(inclusive) to to (exclusive, defaults to now), at most 31 days. Groups without data are omitted.")
    public MetricSummary metricSummary(
            @RequestParam @Parameter(description = "Canonical metric, e.g. humidity") String metric,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "site") @Parameter(description = "site or device") String groupBy,
            @RequestParam(required = false) @Parameter(description = "Restrict to one site") String siteId) {
        return fleet.metricSummary(metric, from, to == null ? clock.instant() : to, parseGroupBy(groupBy), siteId);
    }

    @GetMapping("/status")
    @Operation(operationId = "getFleetStatus", summary = "Reporting and silent devices per site",
            description = "Device counts per site and the most recently seen silent devices (at most 100 per site).")
    public List<SiteStatus> status(@RequestParam(required = false) @Parameter(description = "Restrict to one site") String siteId) {
        return fleet.status(siteId);
    }

    private static GroupBy parseGroupBy(String value) {
        try {
            return GroupBy.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid(Map.of("groupBy", List.of("must be site or device")));
        }
    }
}

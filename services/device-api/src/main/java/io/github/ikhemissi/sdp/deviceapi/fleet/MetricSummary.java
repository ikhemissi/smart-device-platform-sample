package io.github.ikhemissi.sdp.deviceapi.fleet;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/** Summary of one metric over a range, one entry per site or device with data (spec fleet-analytics). */
public record MetricSummary(String metric, String unit, Instant from, Instant to, GroupBy groupBy,
                            @JsonInclude(JsonInclude.Include.NON_NULL) String siteId, List<Group> groups) {

    /**
     * @param deviceId only when grouped by device
     * @param devices  devices with at least one value in the group
     */
    public record Group(String siteId, @JsonInclude(JsonInclude.Include.NON_NULL) String deviceId,
                        double min, double avg, double max, long samples, int devices, Reading first, Reading last) {
    }

    public record Reading(Instant at, double value) {
    }

    @Schema(enumAsRef = true)
    public enum GroupBy {
        site, device
    }
}

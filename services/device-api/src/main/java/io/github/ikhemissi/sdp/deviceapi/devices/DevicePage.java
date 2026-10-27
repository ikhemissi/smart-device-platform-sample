package io.github.ikhemissi.sdp.deviceapi.devices;

import java.util.List;

/** One page of devices; {@code total} counts every device matching the filters. */
public record DevicePage(List<DeviceSummary> items, int page, int pageSize, long total) {
}

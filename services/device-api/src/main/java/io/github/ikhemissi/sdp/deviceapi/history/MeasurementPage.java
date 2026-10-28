package io.github.ikhemissi.sdp.deviceapi.history;

import java.util.List;

/** Measurements in ascending time order; {@code nextCursor} is {@code null} on the last page. */
public record MeasurementPage(List<Measurement> items, String nextCursor) {
}

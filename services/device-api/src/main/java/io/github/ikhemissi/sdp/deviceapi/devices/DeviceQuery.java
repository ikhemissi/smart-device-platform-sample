package io.github.ikhemissi.sdp.deviceapi.devices;

/** Validated list criteria; {@code page} is 1-based. */
public record DeviceQuery(String siteId, DeviceStatus status, String search, SortField sort, boolean descending,
                          int page, int pageSize) {

    public enum SortField {
        DISPLAY_NAME("displayName", "coalesce(d.display_name, d.device_id)"),
        SITE_ID("siteId", "d.site_id"),
        FIRST_SEEN("firstSeen", "d.first_seen_at"),
        LAST_SEEN("lastSeen", "d.last_seen_at");

        private final String parameter;
        private final String column;

        SortField(String parameter, String column) {
            this.parameter = parameter;
            this.column = column;
        }

        String column() {
            return column;
        }

        public static SortField parse(String parameter) {
            for (SortField field : values()) {
                if (field.parameter.equals(parameter)) {
                    return field;
                }
            }
            throw new IllegalArgumentException(parameter);
        }
    }

    int offset() {
        return (page - 1) * pageSize;
    }
}

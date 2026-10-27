package io.github.ikhemissi.sdp.platform.security;

/**
 * Permission required by an API operation. {@link #READ} and {@link #WRITE} map to the Entra app roles of the platform
 * API; {@link #AUTHENTICATED} only requires a valid token (used by {@code GET /me} so that users without a role can
 * learn what to request).
 */
public enum SdpRole {

    AUTHENTICATED("authenticated", null),
    READ("read", "DeviceData.Read"),
    WRITE("write", "DeviceData.Write");

    private final String openApiValue;
    private final String appRole;

    SdpRole(String openApiValue, String appRole) {
        this.openApiValue = openApiValue;
        this.appRole = appRole;
    }

    /** Value of the {@code x-sdp-required-role} OpenAPI extension, read by the gateway policies. */
    public String openApiValue() {
        return openApiValue;
    }

    /** Entra app role value, also used as the Spring Security authority; {@code null} for {@link #AUTHENTICATED}. */
    public String appRole() {
        return appRole;
    }
}

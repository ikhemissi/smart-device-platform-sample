package io.github.ikhemissi.sdp.provisioning.azure;

/** An Azure call that failed (unreachable, throttled, refused or unexpected answer). */
public class AzureRestException extends RuntimeException {

    private final int status;

    public AzureRestException(String message, int status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    /** HTTP status, or 0 when the service could not be reached. */
    public int status() {
        return status;
    }

    public boolean isPreconditionFailed() {
        return status == 412;
    }
}

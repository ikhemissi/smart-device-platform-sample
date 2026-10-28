package io.github.ikhemissi.sdp.provisioning.sites;

/** DPS or IoT Hub could not be updated: nothing was changed. */
public class ProvisioningUnavailableException extends RuntimeException {

    public ProvisioningUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

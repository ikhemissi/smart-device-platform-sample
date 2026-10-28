package io.github.ikhemissi.sdp.provisioning.certificates;

/** An uploaded certificate that cannot be trusted; the message is safe to show (never contains uploaded content). */
public class CertificateRejectedException extends RuntimeException {

    public CertificateRejectedException(String message) {
        super(message, null, false, false);
    }
}

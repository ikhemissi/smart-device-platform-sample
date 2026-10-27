package io.github.ikhemissi.sdp.simulator.certs;

import com.microsoft.azure.sdk.iot.provisioning.security.SecurityProviderX509;
import io.github.ikhemissi.sdp.simulator.certs.DeviceCertificateFactory.DeviceCredentials;
import java.security.Key;
import java.security.cert.X509Certificate;
import java.util.Collection;

/** Presents in-memory device credentials to DPS and IoT Hub (X.509 attestation, no keys on disk). */
public final class X509DeviceSecurityProvider extends SecurityProviderX509 {

    private final DeviceCredentials credentials;

    public X509DeviceSecurityProvider(DeviceCredentials credentials) {
        this.credentials = credentials;
    }

    @Override
    public String getClientCertificateCommonName() {
        return credentials.deviceId();
    }

    @Override
    public X509Certificate getClientCertificate() {
        return credentials.certificate();
    }

    @Override
    public Key getClientPrivateKey() {
        return credentials.privateKey();
    }

    /** The site CA is uploaded to the site's enrollment group, so the device presents it in its chain. */
    @Override
    public Collection<X509Certificate> getIntermediateCertificatesChain() {
        return credentials.chain();
    }
}

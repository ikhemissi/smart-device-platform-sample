package io.github.ikhemissi.sdp.provisioning.azure;

import java.util.Optional;

/** CA certificates registered (as verified) in DPS (control plane). */
public interface DpsCertificates {

    record RegisteredCertificate(String name, String pem, String etag) {
    }

    /** Registers the certificate as verified under {@code name}; DPS refuses (412) a name that is already registered. */
    void register(String name, String pem);

    Optional<RegisteredCertificate> find(String name);

    /** Removes the certificate; nothing happens when it does not exist. */
    void delete(String name);
}

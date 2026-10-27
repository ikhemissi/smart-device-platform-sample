package io.github.ikhemissi.sdp.simulator.certs;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

/** A site certificate authority: certificate and private key (demo only — production uses the customer's PKI). */
public record SiteCa(X509Certificate certificate, PrivateKey privateKey) {

    /** Parses a PEM bundle holding the CA certificate and its private key (PKCS#8, or OpenSSL traditional EC/RSA format). */
    public static SiteCa fromPem(String pem) {
        X509Certificate certificate = null;
        PrivateKey key = null;
        JcaPEMKeyConverter keys = new JcaPEMKeyConverter();
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object object;
            while ((object = parser.readObject()) != null) {
                if (object instanceof X509CertificateHolder holder && certificate == null) {
                    certificate = new JcaX509CertificateConverter().getCertificate(holder);
                } else if (object instanceof PEMKeyPair pair) {
                    key = pair.getPublicKeyInfo() != null ? keys.getKeyPair(pair).getPrivate() : keys.getPrivateKey(pair.getPrivateKeyInfo());
                } else if (object instanceof PrivateKeyInfo info) {
                    key = keys.getPrivateKey(info);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("invalid PEM bundle", e);
        } catch (CertificateException e) {
            throw new IllegalArgumentException("invalid certificate in PEM bundle", e);
        }
        if (certificate == null || key == null) {
            throw new IllegalArgumentException("PEM bundle must contain a certificate and a private key");
        }
        if (certificate.getBasicConstraints() < 0) {
            throw new IllegalArgumentException("site certificate is not a certificate authority: " + certificate.getSubjectX500Principal());
        }
        return new SiteCa(certificate, key);
    }
}

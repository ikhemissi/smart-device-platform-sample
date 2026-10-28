package io.github.ikhemissi.sdp.provisioning.certificates;

import java.io.StringWriter;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/** Generates CA and device certificates for tests (EC P-256, like the demo site CAs). */
public final class TestCertificates {

    private static final AtomicLong SERIAL = new AtomicLong(System.nanoTime());

    public record Generated(X509Certificate certificate, KeyPair keys) {

        public String pem() {
            return TestCertificates.pem(certificate);
        }

        public String pemWithKey() {
            try (StringWriter out = new StringWriter(); JcaPEMWriter writer = new JcaPEMWriter(out)) {
                writer.writeObject(certificate);
                writer.writeObject(keys.getPrivate());
                writer.flush();
                return out.toString();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        public byte[] der() {
            try {
                return certificate.getEncoded();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private TestCertificates() {
    }

    public static Generated ca(String commonName) {
        return ca(commonName, Instant.now().minus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(365)));
    }

    public static Generated ca(String commonName, Instant notBefore, Instant notAfter) {
        KeyPair keys = keys();
        return new Generated(build(commonName, commonName, keys, keys, notBefore, notAfter, true), keys);
    }

    /** A device certificate issued by {@code issuer}. */
    public static Generated device(String commonName, Generated issuer) {
        KeyPair keys = keys();
        String issuerName = issuer.certificate().getSubjectX500Principal().getName();
        return new Generated(build(commonName, issuerName, keys, issuer.keys(),
                Instant.now().minus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(365)), false), keys);
    }

    public static String pem(X509Certificate certificate) {
        try (StringWriter out = new StringWriter(); JcaPEMWriter writer = new JcaPEMWriter(out)) {
            writer.writeObject(certificate);
            writer.flush();
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static X509Certificate build(String subject, String issuer, KeyPair subjectKeys, KeyPair issuerKeys,
                                         Instant notBefore, Instant notAfter, boolean ca) {
        try {
            X500Name subjectName = subject.contains("=") ? new X500Name(subject) : new X500Name("CN=" + subject);
            X500Name issuerName = issuer.contains("=") ? new X500Name(issuer) : new X500Name("CN=" + issuer);
            X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(issuerName,
                    BigInteger.valueOf(SERIAL.incrementAndGet()), Date.from(notBefore), Date.from(notAfter), subjectName,
                    subjectKeys.getPublic());
            builder.addExtension(Extension.basicConstraints, true, ca ? new BasicConstraints(0) : new BasicConstraints(false));
            builder.addExtension(Extension.keyUsage, true, ca
                    ? new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign)
                    : new KeyUsage(KeyUsage.digitalSignature));
            return new JcaX509CertificateConverter().getCertificate(
                    builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(issuerKeys.getPrivate())));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair keys() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

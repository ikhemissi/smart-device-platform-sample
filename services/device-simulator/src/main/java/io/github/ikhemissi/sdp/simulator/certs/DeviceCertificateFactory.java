package io.github.ikhemissi.sdp.simulator.certs;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Issues device (leaf) certificates signed by a site CA, in memory (M1 design D6). The subject CN is the device id,
 * which DPS uses as registration id and IoT Hub device id (ADR-0006).
 */
public final class DeviceCertificateFactory {

    /** Leaf certificate, its private key and the chain to present (the site CA). */
    public record DeviceCredentials(String deviceId, X509Certificate certificate, PrivateKey privateKey, List<X509Certificate> chain) {
    }

    private static final Duration VALIDITY = Duration.ofDays(365);
    private static final Duration CLOCK_SKEW = Duration.ofHours(1);

    private final SecureRandom random = new SecureRandom();

    public DeviceCredentials issue(SiteCa ca, String deviceId, Instant now) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"), random);
            KeyPair keys = generator.generateKeyPair();

            JcaX509ExtensionUtils extensions = new JcaX509ExtensionUtils();
            X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    ca.certificate(),
                    new BigInteger(63, random).add(BigInteger.ONE),
                    Date.from(now.minus(CLOCK_SKEW)),
                    Date.from(now.plus(VALIDITY)),
                    new X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, deviceId).build(),
                    keys.getPublic())
                    .addExtension(Extension.basicConstraints, true, new BasicConstraints(false))
                    .addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature))
                    .addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth))
                    .addExtension(Extension.subjectKeyIdentifier, false, extensions.createSubjectKeyIdentifier(keys.getPublic()))
                    .addExtension(Extension.authorityKeyIdentifier, false, extensions.createAuthorityKeyIdentifier(ca.certificate()));

            String algorithm = "EC".equals(ca.privateKey().getAlgorithm()) ? "SHA256withECDSA" : "SHA256withRSA";
            X509Certificate certificate = new JcaX509CertificateConverter()
                    .getCertificate(builder.build(new JcaContentSignerBuilder(algorithm).build(ca.privateKey())));
            return new DeviceCredentials(deviceId, certificate, keys.getPrivate(), List.of(ca.certificate()));
        } catch (GeneralSecurityException | OperatorCreationException | java.io.IOException e) {
            throw new IllegalStateException("cannot issue certificate for device " + deviceId, e);
        }
    }
}

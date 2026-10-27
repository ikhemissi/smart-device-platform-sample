package io.github.ikhemissi.sdp.simulator.certs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ikhemissi.sdp.simulator.certs.DeviceCertificateFactory.DeviceCredentials;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import javax.naming.ldap.LdapName;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Task 5.2 — device certificates chain to the site CA and carry the device id as CN. */
class DeviceCertificateFactoryTest {

    private static final String DEVICE = "lyon-01-bthome-004";

    static KeyPair keyPair(String algorithm) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm);
        if ("EC".equals(algorithm)) {
            generator.initialize(new ECGenParameterSpec("secp256r1"));
        } else {
            generator.initialize(2048);
        }
        return generator.generateKeyPair();
    }

    static X509Certificate certificate(KeyPair keys, String cn, boolean ca) throws Exception {
        X500Name name = new X500Name("CN=" + cn);
        Instant now = Instant.now();
        var builder = new JcaX509v3CertificateBuilder(name, BigInteger.TEN, Date.from(now.minus(1, ChronoUnit.HOURS)),
                Date.from(now.plus(365, ChronoUnit.DAYS)), name, keys.getPublic())
                .addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
        String algorithm = "EC".equals(keys.getPrivate().getAlgorithm()) ? "SHA256withECDSA" : "SHA256withRSA";
        return new JcaX509CertificateConverter().getCertificate(builder.build(new JcaContentSignerBuilder(algorithm).build(keys.getPrivate())));
    }

    /** PEM bundle as stored in Key Vault: certificate + PKCS#8 private key (traditional RSA key when pkcs8 is false). */
    static String pemBundle(X509Certificate certificate, KeyPair keys, boolean pkcs8) throws Exception {
        StringWriter out = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(out)) {
            writer.writeObject(certificate);
            writer.writeObject(pkcs8 ? new JcaPKCS8Generator(keys.getPrivate(), null) : keys.getPrivate());
        }
        return out.toString();
    }

    @ParameterizedTest
    @ValueSource(strings = {"EC:pkcs8", "RSA:pkcs8", "RSA:traditional"})
    void issuedCertificateChainsToTheSiteCaWithDeviceIdAsCommonName(String variant) throws Exception {
        String[] parts = variant.split(":");
        KeyPair caKeys = keyPair(parts[0]);
        X509Certificate caCertificate = certificate(caKeys, "lyon-01 demo CA", true);
        SiteCa ca = SiteCa.fromPem(pemBundle(caCertificate, caKeys, "pkcs8".equals(parts[1])));

        DeviceCredentials credentials = new DeviceCertificateFactory().issue(ca, DEVICE, Instant.now());

        X509Certificate leaf = credentials.certificate();
        leaf.verify(caCertificate.getPublicKey());
        leaf.checkValidity();
        assertThat(new LdapName(leaf.getSubjectX500Principal().getName()).getRdns())
                .singleElement().satisfies(rdn -> {
                    assertThat(rdn.getType()).isEqualTo("CN");
                    assertThat(rdn.getValue()).isEqualTo(DEVICE);
                });
        assertThat(leaf.getIssuerX500Principal()).isEqualTo(caCertificate.getSubjectX500Principal());
        assertThat(leaf.getBasicConstraints()).as("not a CA").isEqualTo(-1);
        assertThat(leaf.getExtendedKeyUsage()).containsExactly("1.3.6.1.5.5.7.3.2");
        assertThat(credentials.chain()).containsExactly(caCertificate);
        assertThat(credentials.privateKey().getAlgorithm()).isEqualTo("EC");
    }

    @Test
    void securityProviderBuildsATlsContextForTheDevice() throws Exception {
        KeyPair caKeys = keyPair("EC");
        SiteCa ca = SiteCa.fromPem(pemBundle(certificate(caKeys, "paris-01 demo CA", true), caKeys, true));
        X509DeviceSecurityProvider provider = new X509DeviceSecurityProvider(new DeviceCertificateFactory().issue(ca, DEVICE, Instant.now()));

        assertThat(provider.getRegistrationId()).isEqualTo(DEVICE);
        assertThat(provider.getSSLContext()).isNotNull();
    }

    /** The demo bootstrap generates CAs with OpenSSL; its traditional "EC PRIVATE KEY" format must be accepted. */
    @Test
    void acceptsOpenSslTraditionalEcPrivateKey(@TempDir Path directory) throws Exception {
        Path keyFile = directory.resolve("ca.key");
        Process process = new ProcessBuilder("openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", keyFile.toString())
                .redirectErrorStream(true).start();
        org.junit.jupiter.api.Assumptions.assumeTrue(process.waitFor() == 0, "openssl not available");
        String keyPem = Files.readString(keyFile);
        KeyPair keys;
        try (var parser = new org.bouncycastle.openssl.PEMParser(new java.io.StringReader(keyPem))) {
            keys = new org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter().getKeyPair((org.bouncycastle.openssl.PEMKeyPair) parser.readObject());
        }
        StringWriter certPem = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(certPem)) {
            writer.writeObject(certificate(keys, "openssl CA", true));
        }

        SiteCa ca = SiteCa.fromPem(certPem + keyPem);

        new DeviceCertificateFactory().issue(ca, DEVICE, Instant.now()).certificate().verify(ca.certificate().getPublicKey());
    }

    @Test
    void bundleWithoutCaCertificateOrKeyIsRefused() throws Exception {
        KeyPair keys = keyPair("EC");
        X509Certificate leaf = certificate(keys, "not-a-ca", false);
        assertThatThrownBy(() -> SiteCa.fromPem(pemBundle(leaf, keys, true))).hasMessageContaining("not a certificate authority");

        StringWriter certOnly = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(certOnly)) {
            writer.writeObject(certificate(keys, "ca", true));
        }
        assertThatThrownBy(() -> SiteCa.fromPem(certOnly.toString())).hasMessageContaining("private key");
    }

    @Test
    void directoryProviderReadsSiteBundles(@TempDir Path directory) throws Exception {
        KeyPair keys = keyPair("EC");
        X509Certificate caCertificate = certificate(keys, "marseille-01 demo CA", true);
        Files.writeString(directory.resolve("site-marseille-01-ca.pem"), pemBundle(caCertificate, keys, true));

        assertThat(SiteCaProvider.directory(directory).siteCa("marseille-01").certificate()).isEqualTo(caCertificate);
    }
}

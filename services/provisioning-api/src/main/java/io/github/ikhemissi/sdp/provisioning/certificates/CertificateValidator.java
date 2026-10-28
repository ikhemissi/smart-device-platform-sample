package io.github.ikhemissi.sdp.provisioning.certificates;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.security.auth.x500.X500Principal;

/**
 * Accepts exactly one X.509 certificate (PEM or DER, at most 64 KB) that is a currently valid certificate authority
 * (M4 design D4). Uploads containing a private key are refused before anything is parsed; their content is never
 * logged nor kept.
 */
public class CertificateValidator {

    public static final int MAX_BYTES = 64 * 1024;
    private static final Pattern PEM_CERTIFICATE = Pattern.compile(
            "-----BEGIN CERTIFICATE-----\\s*([A-Za-z0-9+/=\\s]+?)\\s*-----END CERTIFICATE-----");

    private final Clock clock;

    public CertificateValidator(Clock clock) {
        this.clock = clock;
    }

    public CaCertificate validate(byte[] upload) {
        if (upload == null || upload.length == 0) {
            throw new CertificateRejectedException("The file is empty.");
        }
        if (upload.length > MAX_BYTES) {
            throw new CertificateRejectedException("The file is larger than 64 KB; upload a single certificate.");
        }
        String text = new String(upload, StandardCharsets.ISO_8859_1);
        if (text.contains("PRIVATE KEY")) {
            throw new CertificateRejectedException(
                    "The file contains a private key. Upload only the certificate; the private key was discarded.");
        }
        X509Certificate certificate = parse(upload, text);
        if (certificate.getBasicConstraints() < 0) {
            throw new CertificateRejectedException(
                    "A certificate authority certificate is required; this is a device (end-entity) certificate.");
        }
        try {
            certificate.checkValidity(Date.from(clock.instant()));
        } catch (CertificateExpiredException e) {
            throw new CertificateRejectedException("The certificate is expired (valid until "
                    + certificate.getNotAfter().toInstant() + ").");
        } catch (CertificateNotYetValidException e) {
            throw new CertificateRejectedException("The certificate is not valid yet (valid from "
                    + certificate.getNotBefore().toInstant() + ").");
        }
        byte[] der = encoded(certificate);
        return new CaCertificate(
                certificate.getSubjectX500Principal().getName(X500Principal.RFC2253),
                certificate.getIssuerX500Principal().getName(X500Principal.RFC2253),
                fingerprint(der),
                certificate.getNotBefore().toInstant(),
                certificate.getNotAfter().toInstant(),
                pem(der));
    }

    /** Parses a certificate stored by DPS (trusted source), without validity checks. */
    public static CaCertificate describe(String pem) {
        X509Certificate certificate = parse(pem.getBytes(StandardCharsets.US_ASCII), pem);
        byte[] der = encoded(certificate);
        return new CaCertificate(
                certificate.getSubjectX500Principal().getName(X500Principal.RFC2253),
                certificate.getIssuerX500Principal().getName(X500Principal.RFC2253),
                fingerprint(der),
                certificate.getNotBefore().toInstant(),
                certificate.getNotAfter().toInstant(),
                pem(der));
    }

    private static X509Certificate parse(byte[] upload, String text) {
        byte[] der;
        if (text.contains("-----BEGIN")) {
            Matcher matcher = PEM_CERTIFICATE.matcher(text);
            if (!matcher.find()) {
                throw new CertificateRejectedException("The file is not an X.509 certificate (PEM or DER).");
            }
            String body = matcher.group(1);
            if (matcher.find()) {
                throw new CertificateRejectedException("The file contains several certificates; upload exactly one.");
            }
            try {
                der = Base64.getMimeDecoder().decode(body);
            } catch (IllegalArgumentException e) {
                throw new CertificateRejectedException("The file is not an X.509 certificate (PEM or DER).");
            }
        } else {
            der = upload;
        }
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
        } catch (CertificateException | ClassCastException e) {
            throw new CertificateRejectedException("The file is not an X.509 certificate (PEM or DER).");
        }
    }

    private static byte[] encoded(X509Certificate certificate) {
        try {
            return certificate.getEncoded();
        } catch (CertificateEncodingException e) {
            throw new CertificateRejectedException("The file is not an X.509 certificate (PEM or DER).");
        }
    }

    static String fingerprint(byte[] der) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(der));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String pem(byte[] der) {
        return "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END CERTIFICATE-----\n";
    }
}

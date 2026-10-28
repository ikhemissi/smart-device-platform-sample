package io.github.ikhemissi.sdp.provisioning.certificates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** Spec site-certificates "Add a site certificate": every rejection scenario (M4 task 2.1). */
class CertificateValidatorTest {

    private final CertificateValidator validator = new CertificateValidator(Clock.systemUTC());

    @Test
    void acceptsAValidCaInPemOrDer() {
        TestCertificates.Generated ca = TestCertificates.ca("CN=nice-01 site CA,O=Contoso");

        CaCertificate fromPem = validator.validate(ca.pem().getBytes(StandardCharsets.US_ASCII));
        CaCertificate fromDer = validator.validate(ca.der());

        assertThat(fromPem).isEqualTo(fromDer);
        assertThat(fromPem.subject()).contains("CN=nice-01 site CA").contains("O=Contoso");
        assertThat(fromPem.fingerprint()).matches("[0-9a-f]{64}");
        assertThat(fromPem.notAfter()).isAfter(Instant.now());
        assertThat(fromPem.pem()).startsWith("-----BEGIN CERTIFICATE-----");
        assertThat(fromPem.dpsName("nice-01")).isEqualTo("site-nice-01-" + fromPem.fingerprint().substring(0, 12));
    }

    @Test
    void rejectsADeviceCertificate() {
        TestCertificates.Generated device = TestCertificates.device("nice-01-dev-1", TestCertificates.ca("ca"));

        assertThatThrownBy(() -> validator.validate(device.pem().getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOf(CertificateRejectedException.class)
                .hasMessageContaining("certificate authority certificate is required");
    }

    @Test
    void rejectsAPrivateKeyWithoutLoggingOrEchoingIt() {
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.addAppender(logs);
        try {
            String bundle = TestCertificates.ca("ca").pemWithKey();
            String keyBody = bundle.substring(bundle.indexOf("PRIVATE KEY-----") + 16, bundle.indexOf("PRIVATE KEY-----") + 40).strip();

            assertThatThrownBy(() -> validator.validate(bundle.getBytes(StandardCharsets.US_ASCII)))
                    .isInstanceOf(CertificateRejectedException.class)
                    .hasMessageContaining("private key")
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain(keyBody))
                    .satisfies(e -> assertThat(e.getStackTrace()).isEmpty());
            assertThat(logs.list).noneMatch(event -> event.getFormattedMessage().contains(keyBody));
        } finally {
            root.detachAppender(logs);
        }
    }

    @Test
    void rejectsAnExpiredOrNotYetValidCertificate() {
        Instant now = Instant.now();
        TestCertificates.Generated expired = TestCertificates.ca("old", now.minus(Duration.ofDays(400)), now.minus(Duration.ofDays(1)));
        TestCertificates.Generated future = TestCertificates.ca("future", now.plus(Duration.ofDays(1)), now.plus(Duration.ofDays(400)));

        assertThatThrownBy(() -> validator.validate(expired.der())).hasMessageContaining("expired");
        assertThatThrownBy(() -> validator.validate(future.der())).hasMessageContaining("not valid yet");
    }

    @Test
    void rejectsOtherContent() {
        String two = TestCertificates.ca("a").pem() + TestCertificates.ca("b").pem();

        assertThatThrownBy(() -> validator.validate(new byte[0])).hasMessageContaining("empty");
        assertThatThrownBy(() -> validator.validate(new byte[CertificateValidator.MAX_BYTES + 1])).hasMessageContaining("64 KB");
        assertThatThrownBy(() -> validator.validate("hello".getBytes(StandardCharsets.US_ASCII))).hasMessageContaining("not an X.509 certificate");
        assertThatThrownBy(() -> validator.validate(two.getBytes(StandardCharsets.US_ASCII))).hasMessageContaining("several certificates");
        assertThatThrownBy(() -> validator.validate("-----BEGIN CERTIFICATE-----\n!!!\n-----END CERTIFICATE-----".getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOf(CertificateRejectedException.class);
    }

    @Test
    void describesCertificatesStoredInDps() {
        TestCertificates.Generated ca = TestCertificates.ca("CN=paris-01 demo site CA");

        assertThat(CertificateValidator.describe(ca.pem()).fingerprint())
                .isEqualTo(validator.validate(ca.der()).fingerprint());
    }
}

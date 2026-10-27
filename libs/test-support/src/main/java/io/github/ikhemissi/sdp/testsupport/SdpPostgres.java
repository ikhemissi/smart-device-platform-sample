package io.github.ikhemissi.sdp.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * PostgreSQL 18 + pg_partman for integration tests, built from {@code sdp-postgres/Dockerfile}. The image tag
 * contains a hash of the Dockerfile so it is rebuilt only when the definition changes. Works with Docker and
 * Podman (set {@code DOCKER_HOST} to the Podman socket).
 */
public final class SdpPostgres {

    public static final String DATABASE = "sdp";
    public static final String ADMIN_USER = "sdp_admin";
    public static final String ADMIN_PASSWORD = "test-only";

    private static final String DOCKERFILE = "sdp-postgres/Dockerfile";
    private static PostgreSQLContainer shared;

    private SdpPostgres() {
    }

    /** A new, not yet started container. */
    public static PostgreSQLContainer newContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(DockerImageName.parse(image()).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName(DATABASE)
                .withUsername(ADMIN_USER)
                .withPassword(ADMIN_PASSWORD);
        ContainerEngine.adapt(container);
        return container;
    }

    /** A started container shared by all tests of the JVM; stopped when the JVM exits. */
    public static synchronized PostgreSQLContainer shared() {
        if (shared == null) {
            shared = newContainer();
            shared.start();
            PostgreSQLContainer container = shared;
            Runtime.getRuntime().addShutdownHook(new Thread(container::stop, "sdp-postgres-stop"));
        }
        return shared;
    }

    private static String image() {
        String name = "localhost/sdp-postgres-test:18-" + dockerfileHash();
        try {
            DockerClientFactory.instance().client().inspectImageCmd(name).exec();
            return name;
        } catch (RuntimeException notFound) {
            return new ImageFromDockerfile(name, false).withFileFromClasspath("Dockerfile", DOCKERFILE).get();
        }
    }

    private static String dockerfileHash() {
        try (InputStream in = SdpPostgres.class.getClassLoader().getResourceAsStream(DOCKERFILE)) {
            if (in == null) {
                throw new IllegalStateException(DOCKERFILE + " not found on classpath");
            }
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(in.readAllBytes());
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

package io.github.ikhemissi.sdp.testsupport;

import java.util.List;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

/**
 * Adapts containers to the local container engine. Through its Docker-compatible API, rootless Podman attaches
 * containers to its default bridge network, which requires CNI plugins that are often not installed; the
 * {@code podman run} CLI uses slirp4netns instead. Containers are therefore started in slirp4netns mode on
 * rootless Podman (port publishing works the same). Docker and rootful Podman are left untouched.
 */
public final class ContainerEngine {

    private static Boolean rootlessPodman;

    private ContainerEngine() {
    }

    public static <T extends GenericContainer<?>> T adapt(T container) {
        if (isRootlessPodman()) {
            container.withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withNetworkMode("slirp4netns"));
        }
        return container;
    }

    public static synchronized boolean isRootlessPodman() {
        if (rootlessPodman == null) {
            var client = DockerClientFactory.instance().client();
            boolean podman = client.versionCmd().exec().getComponents() != null
                    && client.versionCmd().exec().getComponents().stream().anyMatch(c -> c.getName().startsWith("Podman"));
            List<String> security = client.infoCmd().exec().getSecurityOptions();
            rootlessPodman = podman && security != null && security.stream().anyMatch(o -> o.contains("rootless"));
        }
        return rootlessPodman;
    }
}

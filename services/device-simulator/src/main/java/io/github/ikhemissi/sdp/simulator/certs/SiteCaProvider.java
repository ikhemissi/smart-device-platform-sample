package io.github.ikhemissi.sdp.simulator.certs;

import com.azure.security.keyvault.secrets.SecretClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Supplies the certificate authority of a site; results are cached. */
public interface SiteCaProvider {

    SiteCa siteCa(String siteId);

    /** Name of the Key Vault secret / file holding a site's CA bundle. */
    static String secretName(String siteId) {
        return "site-" + siteId + "-ca";
    }

    /** Reads {@code site-<siteId>-ca} from Key Vault with the workload (or developer) Entra identity. */
    static SiteCaProvider keyVault(SecretClient secrets) {
        Map<String, SiteCa> cache = new ConcurrentHashMap<>();
        return siteId -> cache.computeIfAbsent(siteId,
                id -> SiteCa.fromPem(secrets.getSecret(secretName(id)).getValue()));
    }

    /** Reads {@code <directory>/site-<siteId>-ca.pem} (offline local runs). */
    static SiteCaProvider directory(Path directory) {
        Map<String, SiteCa> cache = new ConcurrentHashMap<>();
        return siteId -> cache.computeIfAbsent(siteId, id -> {
            try {
                return SiteCa.fromPem(Files.readString(directory.resolve(secretName(id) + ".pem")));
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read CA bundle of site " + id, e);
            }
        });
    }
}

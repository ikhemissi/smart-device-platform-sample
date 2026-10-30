package io.github.ikhemissi.sdp.deviceapi.digest;

import io.github.ikhemissi.sdp.deviceapi.web.ApiException;
import io.github.ikhemissi.sdp.platform.security.SdpUser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Site digests (spec site-digest, M8 design D6). */
@Service
public class DigestService {

    private final DigestRepository repository;
    private final DigestProperties properties;

    public DigestService(DigestRepository repository, DigestProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public SiteDigest digest(String siteId) {
        requireSite(siteId);
        if (!properties.digests(siteId)) {
            return new SiteDigest(siteId, false, null, null);
        }
        return new SiteDigest(siteId, true, repository.latest(siteId).orElse(null), repository.pending(siteId).orElse(null));
    }

    /** Records a regeneration request; a pending one is returned as is. */
    @Transactional
    public SiteDigest.PendingRequest request(String siteId, SdpUser user) {
        requireSite(siteId);
        if (!properties.digests(siteId)) {
            throw new ApiException(HttpStatus.CONFLICT, "Site '" + siteId + "' has no digests in this environment.");
        }
        repository.request(siteId, user.displayName() != null ? user.displayName() : user.userPrincipalName());
        return repository.pending(siteId).orElseThrow();
    }

    private void requireSite(String siteId) {
        if (!repository.siteExists(siteId)) {
            throw ApiException.notFound("Site '" + siteId + "' does not exist.");
        }
    }
}

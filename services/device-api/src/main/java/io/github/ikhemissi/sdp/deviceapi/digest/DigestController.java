package io.github.ikhemissi.sdp.deviceapi.digest;

import io.github.ikhemissi.sdp.platform.security.RequiredRole;
import io.github.ikhemissi.sdp.platform.security.SdpRole;
import io.github.ikhemissi.sdp.platform.security.SdpUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredRole(SdpRole.READ)
@Tag(name = "Site digests")
public class DigestController {

    private final DigestService service;

    public DigestController(DigestService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/sites/{siteId}/digest")
    @Operation(operationId = "getSiteDigest", summary = "Latest digest of a site",
            description = "Whether the site gets digests, its latest digest (brief written from the figures, the figures, "
                    + "period and generation time) and the regeneration request not served yet, if any.")
    public SiteDigest digest(@PathVariable String siteId) {
        return service.digest(siteId);
    }

    @PostMapping("/api/v1/sites/{siteId}/digest/requests")
    @RequiredRole(SdpRole.WRITE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(operationId = "requestSiteDigest", summary = "Request a new digest of a site",
            description = "The digest job generates it within minutes, covering the 24 hours before its generation. A request "
                    + "while another is pending returns the pending one. 409 when the site has no digests.")
    public SiteDigest.PendingRequest request(@PathVariable String siteId) {
        return service.request(siteId, SdpUser.current());
    }
}

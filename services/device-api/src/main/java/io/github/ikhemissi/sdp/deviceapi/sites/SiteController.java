package io.github.ikhemissi.sdp.deviceapi.sites;

import io.github.ikhemissi.sdp.platform.security.RequiredRole;
import io.github.ikhemissi.sdp.platform.security.SdpRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Sites")
public class SiteController {

    private final SiteRepository repository;

    public SiteController(SiteRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/api/v1/sites")
    @RequiredRole(SdpRole.READ)
    @Operation(operationId = "listSites", summary = "List sites with their number of devices")
    public List<Site> list() {
        return repository.sitesWithDeviceCounts();
    }
}

package io.github.ikhemissi.sdp.provisioning.sites;

import io.github.ikhemissi.sdp.platform.security.RequiredRole;
import io.github.ikhemissi.sdp.platform.security.SdpRole;
import io.github.ikhemissi.sdp.platform.security.SdpUser;
import io.github.ikhemissi.sdp.provisioning.certificates.CertificateRejectedException;
import io.github.ikhemissi.sdp.provisioning.certificates.CertificateValidator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Tag(name = "Site certificates")
public class SiteController {

    static final String SITE_ID = "^[a-z0-9]+(-[a-z0-9]+)*$";
    private static final String DEVICE_OPERATION_HEADER = "Location";

    private final SiteTrustService service;

    public SiteController(SiteTrustService service) {
        this.service = service;
    }

    @Schema(name = "SiteCreation")
    public record SiteCreation(
            @NotBlank @Size(min = 3, max = 40) @Pattern(regexp = SITE_ID, message = "must use lowercase letters, digits and single hyphens")
            @Schema(example = "nice-01") String siteId,
            @NotBlank @Size(max = 100) @Schema(example = "Nice warehouse") String displayName) {
    }

    public record CreatedSite(String siteId, String displayName, int certificateCount) {
    }

    @PostMapping("/api/v1/sites")
    @RequiredRole(SdpRole.WRITE)
    @Operation(operationId = "createSite", summary = "Create a site",
            description = "A new site trusts no certificate, so no device can join it until a certificate is added.")
    public ResponseEntity<CreatedSite> createSite(@Valid @RequestBody SiteCreation request) {
        service.createSite(request.siteId(), request.displayName().strip(), SdpUser.current());
        return ResponseEntity.created(URI.create("/api/v1/sites/" + request.siteId() + "/certificates"))
                .body(new CreatedSite(request.siteId(), request.displayName().strip(), 0));
    }

    @GetMapping("/api/v1/site-certificates")
    @RequiredRole(SdpRole.READ)
    @Operation(operationId = "listAllSiteCertificates", summary = "Certificates trusted by every site",
            description = "Used for the site list's trust summary.")
    public List<SiteCertificate> allCertificates() {
        return service.allCertificates();
    }

    @GetMapping("/api/v1/sites/{siteId}/certificates")
    @RequiredRole(SdpRole.READ)
    @Operation(operationId = "listSiteCertificates", summary = "Certificates trusted by a site",
            description = "Status is valid, expiring (ends within 30 days) or expired.")
    public List<SiteCertificate> certificates(@PathVariable String siteId) {
        return service.certificates(siteId);
    }

    public record CertificateChange(SiteCertificate certificate, DeviceOperation deviceOperation) {
    }

    @PostMapping(path = "/api/v1/sites/{siteId}/certificates", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiredRole(SdpRole.WRITE)
    @Operation(operationId = "addSiteCertificate", summary = "Trust a CA certificate for a site",
            description = "Upload one X.509 CA certificate (PEM or DER, at most 64 KB) as the multipart part `file`. "
                    + "Rejected when it is not a CA, not currently valid, contains a private key, is already trusted, or "
                    + "the site already trusts two certificates. Re-enables devices disabled when the site lost its last certificate.")
    public ResponseEntity<CertificateChange> addCertificate(@PathVariable String siteId,
            @RequestPart("file") @Schema(type = "string", format = "binary") MultipartFile file) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new CertificateRejectedException("The file could not be read.");
        }
        if (content.length > CertificateValidator.MAX_BYTES) {
            throw new CertificateRejectedException("The file is larger than 64 KB; upload a single certificate.");
        }
        SiteTrustService.Change change = service.addCertificate(siteId, content, SdpUser.current());
        return withOperation(ResponseEntity.status(HttpStatus.CREATED), siteId, change);
    }

    @DeleteMapping("/api/v1/sites/{siteId}/certificates/{fingerprint}")
    @RequiredRole(SdpRole.WRITE)
    @Operation(operationId = "removeSiteCertificate", summary = "Stop trusting a certificate",
            description = "Devices issued by it can no longer join. When it is the site's last certificate, all devices of "
                    + "the site are disabled. 202 when the device operation continues in the background.")
    public ResponseEntity<CertificateChange> removeCertificate(@PathVariable String siteId,
            @PathVariable @Pattern(regexp = "^[0-9a-fA-F]{64}$", message = "must be a SHA-256 fingerprint (64 hex characters)") String fingerprint) {
        SiteTrustService.Change change = service.removeCertificate(siteId, fingerprint, SdpUser.current());
        return withOperation(ResponseEntity.ok(), siteId, change);
    }

    @GetMapping("/api/v1/sites/{siteId}/operations/{operationId}")
    @RequiredRole(SdpRole.READ)
    @Operation(operationId = "getSiteOperation", summary = "Progress of a device disable/re-enable operation")
    public DeviceOperation operation(@PathVariable String siteId, @PathVariable UUID operationId) {
        return service.operation(siteId, operationId);
    }

    @GetMapping("/api/v1/sites/{siteId}/audit")
    @RequiredRole(SdpRole.READ)
    @Operation(operationId = "listSiteAudit", summary = "Trust changes of a site, most recent first")
    public List<AuditEvent> audit(@PathVariable String siteId,
                                  @RequestParam(defaultValue = "50") @Min(value = 1, message = "must be at least 1")
                                  @Max(value = 200, message = "must be at most 200") int limit) {
        return service.audit(siteId, limit);
    }

    private static ResponseEntity<CertificateChange> withOperation(ResponseEntity.BodyBuilder done, String siteId,
                                                                   SiteTrustService.Change change) {
        DeviceOperation op = change.deviceOperation();
        ResponseEntity.BodyBuilder builder = op != null && DeviceOperation.PENDING.equals(op.status())
                ? ResponseEntity.accepted().header(DEVICE_OPERATION_HEADER, "/api/v1/sites/" + siteId + "/operations/" + op.id())
                : done;
        return builder.body(new CertificateChange(change.certificate(), op));
    }
}

package io.github.ikhemissi.sdp.provisioning.sites;

import io.github.ikhemissi.sdp.platform.security.SdpUser;
import io.github.ikhemissi.sdp.provisioning.ProvisioningProperties;
import io.github.ikhemissi.sdp.provisioning.azure.AzureRestException;
import io.github.ikhemissi.sdp.provisioning.azure.DpsCertificates;
import io.github.ikhemissi.sdp.provisioning.azure.EnrollmentGroup;
import io.github.ikhemissi.sdp.provisioning.azure.EnrollmentGroups;
import io.github.ikhemissi.sdp.provisioning.certificates.CaCertificate;
import io.github.ikhemissi.sdp.provisioning.certificates.CertificateValidator;
import io.github.ikhemissi.sdp.provisioning.web.ApiException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Site trust (spec site-certificates, M4 design D2). DPS is authoritative: every change is applied to DPS first
 * (conditional on the enrollment group's entity tag) and recorded in PostgreSQL afterwards, in a transaction holding the
 * site's lock. If DPS cannot be updated, nothing changes (503); if recording fails after DPS changed, reconciliation
 * repairs the records. When the last certificate is removed, the enrollment group is disabled (not deleted, so the
 * deployment bootstrap never re-creates it) and the site's devices are disabled in IoT Hub.
 */
@Service
public class SiteTrustService {

    private static final Logger log = LoggerFactory.getLogger(SiteTrustService.class);

    private final SiteRepository repository;
    private final EnrollmentGroups groups;
    private final DpsCertificates certificates;
    private final CertificateValidator validator;
    private final DeviceOperations deviceOperations;
    private final TransactionTemplate tx;
    private final ProvisioningProperties properties;
    private final Clock clock;
    private final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();

    public SiteTrustService(SiteRepository repository, EnrollmentGroups groups, DpsCertificates certificates,
                            DeviceOperations deviceOperations, TransactionTemplate tx, ProvisioningProperties properties,
                            Clock clock) {
        this.repository = repository;
        this.groups = groups;
        this.certificates = certificates;
        this.validator = new CertificateValidator(clock);
        this.deviceOperations = deviceOperations;
        this.tx = tx;
        this.properties = properties;
        this.clock = clock;
    }

    /** Result of a change, with the device operation it started, if any. */
    public record Change(SiteCertificate certificate, DeviceOperation deviceOperation) {
    }

    public void createSite(String siteId, String displayName, SdpUser user) {
        tx.executeWithoutResult(status -> {
            repository.lockSite(siteId);
            if (!repository.insertSite(siteId, displayName)) {
                throw new ApiException(HttpStatus.CONFLICT, "Site '" + siteId + "' already exists.");
            }
            repository.audit(siteId, user.objectId(), user.displayName(), AuditEvent.SITE_CREATED, null,
                    Map.of("displayName", displayName));
        });
    }

    public List<SiteCertificate> certificates(String siteId) {
        requireSite(siteId);
        return repository.certificates(siteId);
    }

    public List<SiteCertificate> allCertificates() {
        return repository.allCertificates();
    }

    public List<AuditEvent> audit(String siteId, int limit) {
        requireSite(siteId);
        return repository.auditEvents(siteId, limit);
    }

    public DeviceOperation operation(String siteId, UUID id) {
        return repository.operation(id).filter(op -> op.siteId().equals(siteId))
                .orElseThrow(() -> ApiException.notFound("Operation " + id + " does not exist for site " + siteId + "."));
    }

    public Change addCertificate(String siteId, byte[] upload, SdpUser user) {
        CaCertificate ca = validator.validate(upload);
        Change change = tx.execute(status -> {
            repository.lockSite(siteId);
            requireSite(siteId);
            List<SiteCertificate> current = repository.certificates(siteId);
            if (current.stream().anyMatch(c -> c.fingerprint().equals(ca.fingerprint()))) {
                throw new ApiException(HttpStatus.CONFLICT, "This certificate is already trusted by site " + siteId + ".");
            }
            Optional<EnrollmentGroup> existing = dps(() -> groups.get(EnrollmentGroup.idOf(siteId)));
            EnrollmentGroup group = existing.orElse(null);
            boolean trusted = group != null && group.enabled();
            String primary = trusted ? group.primary() : null;
            String secondary = trusted ? group.secondary() : null;
            if (primary != null && secondary != null) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "Site " + siteId + " already trusts two certificates; remove one before adding another.");
            }
            String name = ca.dpsName(siteId);
            // Already registered when re-added after being the last certificate (the disabled group keeps it); DPS
            // refuses to register a name again, so the registered certificate is reused when it is the same one.
            boolean registered = dps(() -> certificates.find(name)).map(held -> {
                if (!CertificateValidator.describe(held.pem()).fingerprint().equals(ca.fingerprint())) {
                    throw new ApiException(HttpStatus.CONFLICT,
                            "The device provisioning service already holds another certificate named " + name + ".");
                }
                return true;
            }).orElse(false);
            if (!registered) {
                dps(() -> {
                    certificates.register(name, ca.pem());
                    return null;
                });
            }
            String slot = primary == null ? "primary" : "secondary";
            EnrollmentGroup updated = (group == null ? EnrollmentGroup.create(siteId) : group)
                    .withReferences(primary == null ? name : primary, primary == null ? secondary : name);
            try {
                dps(() -> groups.put(updated));
            } catch (RuntimeException e) {
                if (!registered) {
                    deleteQuietly(name);
                }
                throw e;
            }
            // References a disabled group still held (from its last certificate) are no longer used.
            if (group != null && !group.enabled()) {
                for (String stale : new String[] {group.primary(), group.secondary()}) {
                    if (stale != null && !stale.equals(name)) {
                        deleteQuietly(stale);
                    }
                }
            }
            SiteCertificate certificate = new SiteCertificate(siteId, ca.fingerprint(), slot, ca.subject(), ca.issuer(),
                    ca.notBefore(), ca.notAfter(), null, user.objectId(), user.displayName(), clock.instant(), name);
            repository.insertCertificate(certificate);
            repository.audit(siteId, user.objectId(), user.displayName(), AuditEvent.CERTIFICATE_ADDED, ca.fingerprint(),
                    Map.of("slot", slot, "subject", ca.subject(), "notAfter", ca.notAfter().toString()));
            DeviceOperation operation = null;
            int disabled = repository.devicesDisabledByPlatform(siteId).size();
            if (disabled > 0) {
                operation = repository.createOperation(siteId, DeviceOperation.ENABLE, disabled);
                repository.supersedePending(siteId, operation.id());
            }
            SiteCertificate stored = repository.certificates(siteId).stream()
                    .filter(c -> c.fingerprint().equals(ca.fingerprint())).findFirst().orElseThrow();
            return new Change(stored, operation);
        });
        return new Change(change.certificate(), runOperation(change.deviceOperation()));
    }

    public Change removeCertificate(String siteId, String fingerprint, SdpUser user) {
        String normalized = fingerprint.toLowerCase();
        String[] removedName = new String[1];
        Change change = tx.execute(status -> {
            repository.lockSite(siteId);
            requireSite(siteId);
            List<SiteCertificate> current = repository.certificates(siteId);
            SiteCertificate removed = current.stream().filter(c -> c.fingerprint().equals(normalized)).findFirst()
                    .orElseThrow(() -> ApiException.notFound("Site " + siteId + " does not trust a certificate with fingerprint " + fingerprint + "."));
            removedName[0] = removed.dpsCertificateName();
            Optional<EnrollmentGroup> group = dps(() -> groups.get(EnrollmentGroup.idOf(siteId)));
            List<String> remaining = new ArrayList<>();
            group.filter(EnrollmentGroup::enabled).ifPresent(g -> {
                for (String ref : new String[] {g.primary(), g.secondary()}) {
                    if (ref != null && !ref.equals(removed.dpsCertificateName())) {
                        remaining.add(ref);
                    }
                }
            });
            boolean last = remaining.isEmpty();
            if (group.isPresent() && group.get().enabled()) {
                EnrollmentGroup updated = last
                        ? group.get().disabled()
                        : group.get().withReferences(remaining.get(0), remaining.size() > 1 ? remaining.get(1) : null);
                dps(() -> groups.put(updated));
            }
            repository.deleteCertificate(siteId, normalized);
            repository.certificates(siteId).stream().filter(c -> "secondary".equals(c.slot()))
                    .forEach(c -> repository.setSlot(siteId, c.fingerprint(), "primary"));
            repository.audit(siteId, user.objectId(), user.displayName(), AuditEvent.CERTIFICATE_REMOVED, normalized,
                    Map.of("slot", removed.slot(), "subject", removed.subject(), "lastCertificate", last));
            DeviceOperation operation = null;
            if (last) {
                operation = repository.createOperation(siteId, DeviceOperation.DISABLE, repository.deviceIds(siteId).size());
                repository.supersedePending(siteId, operation.id());
                // The disabled group keeps referencing the certificate (DPS requires one), so it stays registered.
                removedName[0] = null;
            }
            return new Change(removed, operation);
        });
        if (removedName[0] != null) {
            deleteQuietly(removedName[0]);
        }
        return new Change(change.certificate(), runOperation(change.deviceOperation()));
    }

    /** Small sites complete within the request; larger ones continue in the background (202 with the operation). */
    private DeviceOperation runOperation(DeviceOperation operation) {
        if (operation == null) {
            return null;
        }
        if (operation.deviceCount() <= properties.syncDeviceLimit()) {
            return deviceOperations.run(operation.id());
        }
        background.submit(() -> deviceOperations.run(operation.id()));
        return operation;
    }

    private void requireSite(String siteId) {
        if (!repository.siteExists(siteId)) {
            throw ApiException.notFound("Site '" + siteId + "' does not exist.");
        }
    }

    private void deleteQuietly(String dpsCertificateName) {
        try {
            certificates.delete(dpsCertificateName);
        } catch (RuntimeException e) {
            log.warn("Could not delete unused DPS certificate {} (removed at next reconciliation): {}", dpsCertificateName, e.toString());
        }
    }

    private static <T> T dps(Supplier<T> call) {
        try {
            return call.get();
        } catch (AzureRestException e) {
            if (e.isPreconditionFailed()) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "The site's provisioning configuration changed meanwhile; reload and try again.");
            }
            if (e.status() == 400 || e.status() == 409) {
                log.warn("DPS refused the change: {}", e.getMessage());
                throw new ApiException(HttpStatus.CONFLICT,
                        "The device provisioning service refused the certificate (it may already be registered for another site).");
            }
            log.warn("DPS unavailable: {}", e.getMessage());
            throw new ProvisioningUnavailableException("Device provisioning service unavailable", e);
        }
    }
}

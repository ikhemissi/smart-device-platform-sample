package io.github.ikhemissi.sdp.provisioning.sites;

import io.github.ikhemissi.sdp.provisioning.ProvisioningProperties;
import io.github.ikhemissi.sdp.provisioning.azure.DpsCertificates;
import io.github.ikhemissi.sdp.provisioning.azure.EnrollmentGroup;
import io.github.ikhemissi.sdp.provisioning.azure.EnrollmentGroups;
import io.github.ikhemissi.sdp.provisioning.certificates.CaCertificate;
import io.github.ikhemissi.sdp.provisioning.certificates.CertificateValidator;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Aligns platform records with DPS (M4 design D5), at start-up and every {@code interval}, on one replica at a time:
 * sites and certificates created outside the API (deployment bootstrap) appear, records missed after a partial failure
 * are repaired, records of certificates no longer in DPS are removed — each with a {@code RECONCILED_*} audit entry.
 * Pending device operations are retried.
 */
@Component
public class Reconciliation {

    public static final String ACTOR = "reconciliation";
    private static final Logger log = LoggerFactory.getLogger(Reconciliation.class);

    public record Report(int sites, int added, int removed, int operationsRetried) {
    }

    private final SiteRepository repository;
    private final EnrollmentGroups groups;
    private final DpsCertificates certificates;
    private final DeviceOperations deviceOperations;
    private final SiteLocks locks;
    private final TransactionTemplate tx;
    private final ProvisioningProperties properties;
    private final Clock clock;

    public Reconciliation(SiteRepository repository, EnrollmentGroups groups, DpsCertificates certificates,
                          DeviceOperations deviceOperations, SiteLocks locks, TransactionTemplate tx,
                          ProvisioningProperties properties, Clock clock) {
        this.repository = repository;
        this.groups = groups;
        this.certificates = certificates;
        this.deviceOperations = deviceOperations;
        this.locks = locks;
        this.tx = tx;
        this.properties = properties;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (properties.reconciliation().enabled()) {
            Thread.ofVirtual().name("reconciliation-startup").start(this::runSafely);
        }
    }

    @Scheduled(fixedDelayString = "${sdp.provisioning.reconciliation.interval:1h}",
            initialDelayString = "${sdp.provisioning.reconciliation.interval:1h}")
    public void scheduled() {
        if (properties.reconciliation().enabled()) {
            runSafely();
        }
    }

    private void runSafely() {
        try {
            Report report = run();
            if (report != null) {
                log.info("Reconciliation completed: {} site(s), {} record(s) added, {} removed, {} operation(s) retried",
                        report.sites(), report.added(), report.removed(), report.operationsRetried());
            }
        } catch (RuntimeException e) {
            log.warn("Reconciliation failed, retried at the next run: {}", e.toString());
        }
    }

    /** Runs once; {@code null} if another replica is already reconciling. */
    public Report run() {
        return locks.tryWith("sdp.reconciliation", () -> {
            List<EnrollmentGroup> siteGroups = groups.listSiteGroups();
            int added = 0;
            int removed = 0;
            Set<String> sitesWithGroups = new HashSet<>();
            for (EnrollmentGroup group : siteGroups) {
                sitesWithGroups.add(group.siteId());
                Counts counts = locks.withSite(group.siteId(), () -> reconcile(group.siteId(), Optional.of(group)));
                added += counts.added();
                removed += counts.removed();
            }
            // Records of sites whose group no longer exists in DPS.
            for (String siteId : repository.allCertificates().stream().map(SiteCertificate::siteId).distinct().toList()) {
                if (!sitesWithGroups.contains(siteId)) {
                    Counts counts = locks.withSite(siteId, () -> reconcile(siteId, Optional.empty()));
                    removed += counts.removed();
                }
            }
            int retried = 0;
            for (DeviceOperation op : repository.pendingOperations()) {
                deviceOperations.run(op.id());
                retried++;
            }
            return new Report(siteGroups.size(), added, removed, retried);
        });
    }

    private record Counts(int added, int removed) {
    }

    /** A certificate the site's enrollment group references, by fingerprint. */
    private record ExpectedCertificate(String slot, CaCertificate ca, String dpsName) {
    }

    private Counts reconcile(String siteId, Optional<EnrollmentGroup> groupFromList) {
        // Re-read under the site lock: a trust change may have happened since the list was taken.
        Optional<EnrollmentGroup> group = groupFromList.isPresent() ? groups.get(EnrollmentGroup.idOf(siteId)) : Optional.empty();
        Map<String, ExpectedCertificate> expected = new LinkedHashMap<>();
        group.filter(EnrollmentGroup::enabled).ifPresent(g -> {
            expect(g.primary(), "primary", expected);
            expect(g.secondary(), "secondary", expected);
        });
        return tx.execute(status -> {
            repository.insertSite(siteId, siteId);
            int removed = removeUnexpectedAndFixSlots(siteId, expected);
            int added = addMissing(siteId, expected);
            return new Counts(added, removed);
        });
    }

    /** Deletes records of certificates DPS no longer trusts and moves the others to their DPS slot; returns how many were deleted. */
    private int removeUnexpectedAndFixSlots(String siteId, Map<String, ExpectedCertificate> expected) {
        int removed = 0;
        List<SiteCertificate> moved = new ArrayList<>();
        for (SiteCertificate record : repository.certificates(siteId)) {
            ExpectedCertificate expectation = expected.get(record.fingerprint());
            if (expectation == null) {
                repository.deleteCertificate(siteId, record.fingerprint());
                repository.audit(siteId, ACTOR, "Reconciliation", AuditEvent.RECONCILED_REMOVED, record.fingerprint(),
                        Map.of("reason", "not trusted by the device provisioning service"));
                removed++;
            } else if (!expectation.slot().equals(record.slot())) {
                // Deleted first and re-inserted below: slots are unique per site, so a swap needs both rows out.
                repository.deleteCertificate(siteId, record.fingerprint());
                moved.add(record);
            }
        }
        for (SiteCertificate record : moved) {
            repository.insertCertificate(new SiteCertificate(siteId, record.fingerprint(), expected.get(record.fingerprint()).slot(),
                    record.subject(), record.issuer(), record.notBefore(), record.notAfter(), null, record.addedBy(),
                    record.addedByName(), record.addedAt(), record.dpsCertificateName()));
        }
        return removed;
    }

    /** Records the certificates DPS trusts that the platform does not know; returns how many were added. */
    private int addMissing(String siteId, Map<String, ExpectedCertificate> expected) {
        int added = 0;
        Set<String> recorded = new HashSet<>();
        repository.certificates(siteId).forEach(c -> recorded.add(c.fingerprint()));
        for (Map.Entry<String, ExpectedCertificate> entry : expected.entrySet()) {
            String fingerprint = entry.getKey();
            if (recorded.contains(fingerprint)) {
                continue;
            }
            ExpectedCertificate certificate = entry.getValue();
            CaCertificate ca = certificate.ca();
            String name = certificate.dpsName();
            // Bootstrap CAs are registered under the site's group name (scripts/bootstrap-demo-sites.sh).
            boolean bootstrap = name.equals(EnrollmentGroup.idOf(siteId));
            repository.insertCertificate(new SiteCertificate(siteId, fingerprint, certificate.slot(), ca.subject(), ca.issuer(),
                    ca.notBefore(), ca.notAfter(), null, bootstrap ? "deployment" : ACTOR,
                    bootstrap ? "Deployment" : "Reconciliation", clock.instant(), name));
            repository.audit(siteId, ACTOR, "Reconciliation", AuditEvent.RECONCILED_ADDED, fingerprint,
                    Map.of("slot", certificate.slot(), "subject", ca.subject(), "dpsCertificate", name));
            added++;
        }
        return added;
    }

    private void expect(String dpsName, String slot, Map<String, ExpectedCertificate> expected) {
        if (dpsName == null) {
            return;
        }
        certificates.find(dpsName).ifPresentOrElse(registered -> {
            CaCertificate ca = CertificateValidator.describe(registered.pem());
            expected.put(ca.fingerprint(), new ExpectedCertificate(slot, ca, dpsName));
        }, () -> log.warn("Enrollment group references DPS certificate {} which does not exist", dpsName));
    }
}

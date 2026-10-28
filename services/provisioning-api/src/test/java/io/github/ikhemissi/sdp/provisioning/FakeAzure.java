package io.github.ikhemissi.sdp.provisioning;

import io.github.ikhemissi.sdp.provisioning.azure.AzureRestException;
import io.github.ikhemissi.sdp.provisioning.azure.DeviceRegistry;
import io.github.ikhemissi.sdp.provisioning.azure.DpsCertificates;
import io.github.ikhemissi.sdp.provisioning.azure.EnrollmentGroup;
import io.github.ikhemissi.sdp.provisioning.azure.EnrollmentGroups;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory DPS (groups with entity tags, certificates) and IoT Hub registry, with failure injection. */
public class FakeAzure implements EnrollmentGroups, DpsCertificates, DeviceRegistry {

    public final Map<String, EnrollmentGroup> groups = new ConcurrentHashMap<>();
    public final Map<String, String> certificates = new ConcurrentHashMap<>();
    public final Map<String, Boolean> devices = new ConcurrentHashMap<>();
    private final AtomicInteger versions = new AtomicInteger();
    public volatile boolean unavailable;

    public void reset() {
        groups.clear();
        certificates.clear();
        devices.clear();
        unavailable = false;
    }

    private void check() {
        if (unavailable) {
            throw new AzureRestException("DPS unreachable", 0, null);
        }
    }

    @Override
    public Optional<EnrollmentGroup> get(String groupId) {
        check();
        return Optional.ofNullable(groups.get(groupId));
    }

    @Override
    public synchronized EnrollmentGroup put(EnrollmentGroup group) {
        check();
        EnrollmentGroup current = groups.get(group.id());
        if (group.etag() != null && (current == null || !group.etag().equals(current.etag()))) {
            throw new AzureRestException("etag mismatch", 412, null);
        }
        if (group.etag() == null && current != null) {
            throw new AzureRestException("exists", 409, null);
        }
        for (String ref : new String[] {group.primary(), group.secondary()}) {
            if (ref != null && !certificates.containsKey(ref)) {
                throw new AzureRestException("unknown certificate " + ref, 400, null);
            }
        }
        EnrollmentGroup stored = EnrollmentGroup.parse(group.toJson(), "\"v" + versions.incrementAndGet() + "\"");
        groups.put(group.id(), stored);
        return stored;
    }

    @Override
    public void delete(EnrollmentGroup group) {
        check();
        groups.remove(group.id());
    }

    @Override
    public List<EnrollmentGroup> listSiteGroups() {
        check();
        return new ArrayList<>(groups.values());
    }

    @Override
    public void register(String name, String pem) {
        check();
        if (certificates.containsKey(name)) {
            // As Azure Resource Manager: replacing a certificate needs its ETag.
            throw new AzureRestException("Invalid ETag supplied", 412, null);
        }
        certificates.put(name, pem);
    }

    @Override
    public Optional<RegisteredCertificate> find(String name) {
        check();
        String pem = certificates.get(name);
        return pem == null ? Optional.empty() : Optional.of(new RegisteredCertificate(name, pem, "\"c\""));
    }

    @Override
    public void delete(String name) {
        check();
        for (EnrollmentGroup g : groups.values()) {
            if (name.equals(g.primary()) || name.equals(g.secondary())) {
                throw new AzureRestException("certificate in use", 409, null);
            }
        }
        certificates.remove(name);
    }

    @Override
    public Outcome setEnabled(String deviceId, boolean enabled, String reason) {
        Boolean current = devices.get(deviceId);
        if (current == null) {
            return Outcome.NOT_FOUND;
        }
        if (current == enabled) {
            return Outcome.UNCHANGED;
        }
        devices.put(deviceId, enabled);
        return Outcome.CHANGED;
    }
}

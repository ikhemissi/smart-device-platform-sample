package io.github.ikhemissi.sdp.provisioning.azure;

import java.util.List;
import java.util.Optional;

/** DPS enrollment groups (data plane). Changes are conditional on the group's entity tag. */
public interface EnrollmentGroups {

    Optional<EnrollmentGroup> get(String groupId);

    /** Creates ({@code etag == null}) or replaces the group if unchanged since read; 412 surfaces as an {@link AzureRestException}. */
    EnrollmentGroup put(EnrollmentGroup group);

    /** Deletes the group if unchanged since read. */
    void delete(EnrollmentGroup group);

    /** Every site group ({@code site-*}). */
    List<EnrollmentGroup> listSiteGroups();
}

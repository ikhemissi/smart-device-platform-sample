package io.github.ikhemissi.sdp.deviceapi.digest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DigestPropertiesTest {

    @Test
    void emptySitesMeansEverySite() {
        assertThat(new DigestProperties(true, List.of()).digests("lyon-01")).isTrue();
        assertThat(new DigestProperties(true, null).digests("lyon-01")).isTrue();
    }

    @Test
    void configuredSitesOnly() {
        DigestProperties properties = new DigestProperties(true, List.of(" paris-01", ""));
        assertThat(properties.sites()).containsExactly("paris-01");
        assertThat(properties.digests("paris-01")).isTrue();
        assertThat(properties.digests("lyon-01")).isFalse();
    }

    @Test
    void featureOffMeansNoSite() {
        assertThat(new DigestProperties(false, List.of()).digests("paris-01")).isFalse();
    }
}

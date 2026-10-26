package io.github.ikhemissi.sdp.codecs;

import static io.github.ikhemissi.sdp.codecs.TestSupport.decoded;
import static io.github.ikhemissi.sdp.codecs.TestSupport.rejected;
import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Spec: payload-decoding — "Format detection from the leading byte" and "Encrypted variants are not supported". */
class FormatDetectionTest {

    @Test
    void ruuviDf5PayloadIsRecognised() {
        assertThat(decoded("0512FC5394C37C0004FFFC040CAC364200CDCBB8334C884F").format()).isEqualTo(PayloadFormat.RUUVI_DF5);
    }

    @Test
    void ruuviDf3PayloadIsRecognised() {
        assertThat(decoded("03291A1ECE1EFC18F94202CA0B53").format()).isEqualTo(PayloadFormat.RUUVI_DF3);
    }

    @ParameterizedTest
    @CsvSource({"4002C409", "4402C409"})
    void btHomeV2PayloadIsRecognisedWithOrWithoutTriggerFlag(String payload) {
        assertThat(decoded(payload).format()).isEqualTo(PayloadFormat.BTHOME_V2);
    }

    @Test
    void cayenneMarkerIsRecognised() {
        assertThat(decoded("CA0167FFD7").format()).isEqualTo(PayloadFormat.CAYENNE_LPP);
    }

    @Test
    void unknownLeadingByteIsRejected() {
        DecodeResult.Rejected rejected = rejected("7F0102");

        assertThat(rejected.reason()).isEqualTo(RejectionReason.UNKNOWN_FORMAT);
        assertThat(rejected.detail()).contains("0x7F");
    }

    @Test
    void emptyPayloadIsRejected() {
        assertThat(PayloadCodecs.decode(new byte[0])).isEqualTo(
                new DecodeResult.Rejected(RejectionReason.EMPTY_PAYLOAD, "payload has no bytes"));
        assertThat(PayloadCodecs.decode(null)).isInstanceOf(DecodeResult.Rejected.class);
    }

    @ParameterizedTest
    @CsvSource({"4102C409", "4502C409"})
    void encryptedBtHomePayloadIsUnsupported(String payload) {
        assertThat(rejected(payload).reason()).isEqualTo(RejectionReason.UNSUPPORTED_FORMAT);
    }

    @Property(tries = 2_000)
    void decodingNeverThrowsOnArbitraryBytes(@ForAll @Size(max = 64) byte[] payload) {
        assertThat(PayloadCodecs.decode(payload)).isNotNull();
    }
}

package io.github.ikhemissi.sdp.codecs;

/** Supported device payload formats and their stable identifiers (ADR-0003). */
public enum PayloadFormat {
    RUUVI_DF5("ruuvi-df5"),
    RUUVI_DF3("ruuvi-df3"),
    BTHOME_V2("bthome-v2"),
    CAYENNE_LPP("cayenne-lpp");

    private final String id;

    PayloadFormat(String id) {
        this.id = id;
    }

    /** Identifier stored with measurements and exposed by APIs. */
    public String id() {
        return id;
    }
}

# payload-codecs

Pure Java library (no Spring) that detects a device payload's format **from its first byte only**, decodes it into canonical metrics, and encodes metrics back into the same format (used by the device simulator). It implements [ADR-0003](../../docs/adr/ADR-0003-device-payload-formats.md) and the `payload-decoding` spec of OpenSpec change `m1-device-data-ingestion`.

## Usage

```java
DecodeResult result = PayloadCodecs.decode(payloadBytes);
switch (result) {
    case DecodeResult.Decoded d -> store(d.format().id(), d.metrics(), d.attributes());   // metrics: Map<String, BigDecimal>
    case DecodeResult.Rejected r -> quarantine(r.reason(), r.detail());
}

byte[] payload = PayloadCodecs.forFormat(PayloadFormat.RUUVI_DF5)
        .encode(Map.of("temperature", 21.4, "humidity", 45.0), Map.of("mac", "CB:B8:33:4C:88:4F"));
```

`PayloadCodecs.decode` never throws: any unexpected failure becomes `MALFORMED_PAYLOAD`. Metric values are exact decimals at the format's resolution.

## Format registry

| Leading byte | Format id | Frame | Reference |
|---|---|---|---|
| `0x05` | `ruuvi-df5` | Ruuvi Data Format 5 (RAWv2), exactly 24 bytes | [Ruuvi DF5](https://docs.ruuvi.com/communication/bluetooth-advertisements/data-format-5-rawv2) |
| `0x03` | `ruuvi-df3` | Ruuvi Data Format 3 (RAWv1), exactly 14 bytes | [Ruuvi DF3](https://docs.ruuvi.com/communication/bluetooth-advertisements/data-format-3-rawv1) |
| `0x40`, `0x44` | `bthome-v2` | BTHome v2 unencrypted, from the device-information byte (service UUID stripped), little-endian | [BTHome v2](https://bthome.io/format/) |
| `0xCA` | `cayenne-lpp` | Format marker + standard Cayenne LPP frame, big-endian | [Cayenne LPP](https://docs.mydevices.com/docs/lorawan/cayenne-lpp) |
| `0x41`, `0x45` | — | Encrypted BTHome v2 → `UNSUPPORTED_FORMAT` | |
| anything else | — | `UNKNOWN_FORMAT` | |

Rejection reasons: `EMPTY_PAYLOAD`, `UNKNOWN_FORMAT`, `UNSUPPORTED_FORMAT`, `MALFORMED_PAYLOAD` (wrong length, truncated value, unknown Cayenne type), `NO_SUPPORTED_DATA` (no supported measurement).

## Canonical metrics

| Metric | Unit | Ruuvi DF5 | Ruuvi DF3 | BTHome v2 (object id) | Cayenne LPP (type) |
|---|---|---|---|---|---|
| `temperature` | °C | int16 × 0.005 | sign + int + 1/100 | `0x02` sint16 × 0.01, `0x45` sint16 × 0.1 | `0x67` int16 × 0.1 |
| `humidity` | % | uint16 × 0.0025 | uint8 × 0.5 | `0x03` uint16 × 0.01, `0x2E` uint8 | `0x68` uint8 × 0.5 |
| `pressure` | hPa | (uint16 + 50000 Pa) / 100 | (uint16 + 50000 Pa) / 100 | `0x04` uint24 × 0.01 | `0x73` uint16 × 0.1 |
| `acceleration_x/y/z` | g | int16 mg / 1000 | int16 mg / 1000 | — | `0x71` 3 × int16 × 0.001 |
| `battery_voltage` | V | (11 bits + 1600 mV) / 1000 | uint16 mV / 1000 | `0x0C` uint16 × 0.001 | — |
| `battery_level` | % | — | — | `0x01` uint8 | — |
| `tx_power` | dBm | 5 bits × 2 − 40 | — | — | — |
| `movement_counter` | | uint8 | — | — | — |
| `measurement_sequence` | | uint16 | — | — | — |
| `packet_id` | | — | — | `0x00` uint8 | — |
| `co2` | ppm | — | — | `0x12` uint16 | — |
| `illuminance` | lx | — | — | `0x05` uint24 × 0.01 | `0x65` uint16 |
| `analog_input` / `analog_output` | | — | — | — | `0x02` / `0x03` int16 × 0.01 |
| `digital_input` / `digital_output` | | — | — | — | `0x00` / `0x01` uint8 |
| `presence` | | — | — | — | `0x66` uint8 |
| `latitude` / `longitude` | ° | — | — | — | `0x88` int24 × 0.0001 |
| `altitude` | m | — | — | — | `0x88` int24 × 0.01 |

Attributes (text): `mac` (Ruuvi DF5), `decode_warning` (`UNSUPPORTED_OBJECT_ID:0x..` when BTHome parsing stops early).

## Format-specific rules

- **Not available**: Ruuvi DF5 sentinels (`0x8000` signed, `0xFFFF` unsigned, voltage `2047`, TX power `31`, movement `255`, MAC all `FF`) are omitted from the result. Ruuvi DF3 has no sentinel; missing values are encoded as `0`, as Ruuvi recommends.
- **BTHome v2**: parsing stops at the first unsupported object id (as mandated by BTHome); if nothing was decoded the payload is rejected with `NO_SUPPORTED_DATA`. A metric repeated in one payload is keyed `<metric>_2`, `<metric>_3`, … The encoder writes objects in ascending object-id order (`0x02`/`0x03` for temperature/humidity).
- **Cayenne LPP**: a metric reported on several channels is keyed `<metric>_ch<channel>` (e.g. `temperature_ch3`); otherwise the canonical key is used. The encoder accepts both forms (default channel 1); `acceleration_*` and `latitude`/`longitude`/`altitude` are written as one accelerometer / GPS frame per channel.
- **Encoding** rounds half-up to the format's resolution and clamps to the representable range; keys a format does not support raise `IllegalArgumentException`.

## Tests

`./mvnw -pl libs/payload-codecs test` runs:
- the official test vectors of each maintainer (Ruuvi valid/maximum/minimum vectors in both directions, BTHome and Cayenne documentation examples),
- every scenario of the `payload-decoding` spec,
- jqwik property tests: encode → decode round trips stay within half the format resolution, and decoding arbitrary bytes never throws.

# payload-decoding Specification

## Purpose

Turns the binary payload sent by a device into canonical, unit-normalised metrics, choosing the decoding rules solely from the payload's leading byte, and explains precisely why a payload cannot be decoded.

## Requirements

### Requirement: Format detection from the leading byte
The system SHALL select the payload format exclusively from the first byte of the payload: `0x05` Ruuvi Data Format 5 (`ruuvi-df5`), `0x03` Ruuvi Data Format 3 (`ruuvi-df3`), `0x40` or `0x44` BTHome v2 unencrypted (`bthome-v2`), `0xCA` Cayenne LPP format marker (`cayenne-lpp`). No message property, device metadata or other out-of-band information SHALL influence format selection.

#### Scenario: Ruuvi DF5 payload is recognised
- **WHEN** a payload starting with byte `0x05` is decoded
- **THEN** it is decoded with the `ruuvi-df5` rules and the result reports format `ruuvi-df5`

#### Scenario: BTHome v2 payload with trigger flag is recognised
- **WHEN** a payload starting with byte `0x44` is decoded
- **THEN** it is decoded with the `bthome-v2` rules

#### Scenario: Unknown leading byte
- **WHEN** a payload starting with byte `0x7F` is decoded
- **THEN** decoding fails with reason `UNKNOWN_FORMAT`

#### Scenario: Empty payload
- **WHEN** a zero-length payload is decoded
- **THEN** decoding fails with reason `EMPTY_PAYLOAD`

### Requirement: Encrypted variants are not supported
The system SHALL reject encrypted payload variants (BTHome v2 device-information byte with the encryption bit set, i.e. `0x41` or `0x45`) with reason `UNSUPPORTED_FORMAT`.

#### Scenario: Encrypted BTHome payload
- **WHEN** a payload starting with byte `0x41` is decoded
- **THEN** decoding fails with reason `UNSUPPORTED_FORMAT`

### Requirement: Canonical metrics and units
A decoded payload SHALL be expressed as numeric `metrics` keyed by canonical names with fixed units, plus optional textual `attributes`. Canonical metrics: `temperature` °C, `humidity` %RH, `pressure` hPa, `acceleration_x|y|z` g, `battery_voltage` V, `battery_level` %, `tx_power` dBm, `movement_counter`, `measurement_sequence`, `packet_id`, `co2` ppm, `illuminance` lx, `analog_input`, `analog_output`, `digital_input`, `digital_output`, `presence`, `latitude`/`longitude` °, `altitude` m.

#### Scenario: Units are normalised across formats
- **WHEN** a Ruuvi payload reports pressure 100044 Pa and a BTHome payload reports pressure 1008.83 hPa
- **THEN** the decoded metrics are `pressure = 1000.44` and `pressure = 1008.83` respectively, both in hPa

#### Scenario: Metric catalogue is discoverable
- **WHEN** a consumer asks for the list of canonical metrics
- **THEN** each metric is returned with its name, unit and description

### Requirement: Not-available values are omitted
When a format defines a sentinel meaning "not available" for a field, the system SHALL omit that metric or attribute from the result instead of storing the sentinel value.

#### Scenario: Ruuvi DF5 humidity not available
- **WHEN** a `ruuvi-df5` payload has humidity bytes `0xFFFF`
- **THEN** the decoded metrics contain no `humidity` key and the other metrics are present

### Requirement: Ruuvi Data Format 5 decoding
The system SHALL decode 24-byte `ruuvi-df5` payloads per the Ruuvi RAWv2 specification: temperature (int16 × 0.005), humidity (uint16 × 0.0025), pressure ((uint16 + 50000) Pa → hPa), acceleration (int16 mg → g), battery voltage (11 bits + 1600 mV → V), TX power (5 bits × 2 − 40 dBm), movement counter, measurement sequence, and MAC address as attribute `mac`. Any other length SHALL fail with `MALFORMED_PAYLOAD`.

#### Scenario: Official Ruuvi DF5 test vector
- **WHEN** payload `0512FC5394C37C0004FFFC040CAC364200CDCBB8334C884F` is decoded
- **THEN** metrics are temperature 24.3, humidity 53.49, pressure 1000.44, acceleration_x 0.004, acceleration_y -0.004, acceleration_z 1.036, battery_voltage 2.977, tx_power 4, movement_counter 66, measurement_sequence 205 and attribute `mac` is `CB:B8:33:4C:88:4F`

#### Scenario: Truncated Ruuvi DF5 payload
- **WHEN** a payload starting with `0x05` has 20 bytes
- **THEN** decoding fails with reason `MALFORMED_PAYLOAD`

### Requirement: Ruuvi Data Format 3 decoding
The system SHALL decode 14-byte `ruuvi-df3` payloads per the Ruuvi RAWv1 specification: humidity (uint8 × 0.5), temperature (sign bit + 7-bit integer part + fraction byte in 1/100), pressure ((uint16 + 50000) Pa → hPa), acceleration (int16 mg → g) and battery voltage (uint16 mV → V). Any other length SHALL fail with `MALFORMED_PAYLOAD`.

#### Scenario: Official Ruuvi DF3 test vector
- **WHEN** payload `03291A1ECE1EFC18F94202CA0B53` is decoded
- **THEN** metrics are temperature 26.3, humidity 20.5, pressure 1027.66, acceleration_x -1.0, acceleration_y -1.726, acceleration_z 0.714 and battery_voltage 2.899

#### Scenario: Negative Ruuvi DF3 temperature
- **WHEN** a `ruuvi-df3` payload has temperature bytes `0x81 0x45`
- **THEN** the decoded temperature is -1.69

### Requirement: BTHome v2 decoding
The system SHALL decode `bthome-v2` payloads (device-information byte followed by object-id/value pairs, little-endian) supporting object ids `0x00` packet_id, `0x01` battery_level, `0x02` temperature ×0.01, `0x03` humidity ×0.01, `0x04` pressure uint24 ×0.01, `0x05` illuminance uint24 ×0.01, `0x0C` battery_voltage ×0.001, `0x12` co2, `0x2E` humidity ×1, `0x45` temperature ×0.1.

#### Scenario: BTHome temperature and humidity
- **WHEN** payload `4002C40903BF13` is decoded
- **THEN** metrics are temperature 25.0 and humidity 50.55

#### Scenario: Unsupported object id stops parsing
- **WHEN** a `bthome-v2` payload contains a supported temperature object followed by an unsupported object id
- **THEN** the temperature is returned, parsing stops at the unsupported object id and attribute `decode_warning` is `UNSUPPORTED_OBJECT_ID:0x..`

#### Scenario: No supported object
- **WHEN** a `bthome-v2` payload's first object id is unsupported
- **THEN** decoding fails with reason `NO_SUPPORTED_DATA`

### Requirement: Cayenne LPP decoding
The system SHALL decode `cayenne-lpp` payloads as the `0xCA` marker followed by a standard Cayenne LPP frame (channel, type, value), supporting types digital input/output, analog input/output, illuminance, presence, temperature, humidity, accelerometer, barometer and GPS. A metric appearing on several channels SHALL be keyed `<metric>_ch<channel>`; otherwise the canonical name is used.

#### Scenario: Two temperature channels
- **WHEN** payload `CA03670110056700FF` is decoded
- **THEN** metrics are `temperature_ch3` 27.2 and `temperature_ch5` 25.5

#### Scenario: Single negative temperature
- **WHEN** payload `CA0167FFD7` is decoded
- **THEN** metrics are `temperature` -4.1

#### Scenario: Truncated Cayenne value
- **WHEN** a `cayenne-lpp` payload ends in the middle of a value
- **THEN** decoding fails with reason `MALFORMED_PAYLOAD`

#### Scenario: Unknown Cayenne type
- **WHEN** a `cayenne-lpp` payload contains an unsupported data type
- **THEN** decoding fails with reason `MALFORMED_PAYLOAD` because value lengths can no longer be determined

### Requirement: Encode/decode symmetry for simulated payloads
For every supported format, encoding a set of canonical metrics and decoding the result SHALL yield the original metrics within the format's resolution.

#### Scenario: Round trip within resolution
- **WHEN** the metrics temperature 21.437 and humidity 45.0 are encoded as `ruuvi-df5` and decoded
- **THEN** the decoded temperature equals 21.435 (0.005 °C resolution) and humidity equals 45.0

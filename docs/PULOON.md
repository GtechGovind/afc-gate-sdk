# Puloon GCU

The Puloon implementation targets the GCU interface. Vendor specifications are
not redistributed in this repository; contributors must obtain them through an
authorized vendor or integration channel. The adapter is internal: applications
select `GateVendor.PULOON` and continue using the common `Gate` interface.

## Connection

```kotlin
import com.qurkos.gate.sdk.GateDeviceConfig
import com.qurkos.gate.sdk.GateHardwareProfile
import com.qurkos.gate.sdk.GateMechanism
import com.qurkos.gate.sdk.GateModule
import com.qurkos.gate.sdk.GateProtocolRevision
import com.qurkos.gate.sdk.GateSdk
import com.qurkos.gate.sdk.GateSite
import com.qurkos.gate.sdk.GateVendor
import com.qurkos.gate.sdk.SerialConnectionConfig
import com.qurkos.gate.sdk.SerialPortName

val result = GateSdk.create(
    GateDeviceConfig(
        vendor = GateVendor.PULOON,
        serial = SerialConnectionConfig(SerialPortName("COM4")),
        hardware = GateHardwareProfile(
            mechanism = GateMechanism.SECTOR,
            site = GateSite.KOLKATA_INDIA,
            modules = setOf(GateModule.UPS),
            protocolRevision = GateProtocolRevision.V2_8,
        ),
    ),
)
```

When parameters are omitted, Puloon uses 57,600 baud, 8 data bits, one stop bit, and no parity. A caller may supply explicit `SerialParameters` when its controller is configured differently.

Puloon GCU supports `SECTOR` and `SWING` mechanisms; `FLAP` is rejected by the factory. Set `protocolRevision` to the
controller's interface revision. It defaults to V2.8 for source compatibility, but a V2.5 controller must be configured as
`V2_5` so legacy status values are interpreted correctly and newer extension commands remain unavailable. The hardware
profile matters because the protocol exposes some features only for particular sites, mechanisms, normal-open state, or
installed modules. Configure the actual device; do not select a profile only to enable a capability. Use
`GateSdk.support(config)` before connection to obtain the exact capabilities, pass modes, safety regions, and sensor
identifiers for a profile.

## Passage commands

```kotlin
gate.allowEntry()
gate.allowExit(passengerCount = 2)
gate.rejectPassage(GateDirection.EXIT)
gate.setEmergency(true)
```

India profiles additionally support multi-person passage, lamp selection, invalid-ticket rejection, and clock operations. Kolkata also enables standby policy. UPS shutdown is enabled only when `GateModule.UPS` is present.

## Command coverage

| Common API | Puloon GCU command | Availability |
| --- | --- | --- |
| `allowPassage`, `allowEntry`, `allowExit`, `rejectPassage` | `A` | Base; request options are capability-checked |
| `setEmergency` | `E` | Base |
| `initialize` | `I` | Maintenance opt-in |
| `firmware` | `V` | Base |
| `refreshStatus` | `S` | Base |
| `setPassMode` | `D` | Base |
| `setSafetyRegion` | `G` | Base; legal region depends on mechanism |
| `clearPassageCounters` | `C` | Maintenance opt-in |
| `readSensors` | `H` | Base |
| `readClock`, `setClock` | `X` | India profiles |
| `setUpsShutdownDelaySeconds` | `Y` | UPS module |
| `readStandbyPolicy`, `setStandbyPolicy` | `U` | V2.8 Kolkata profile |
| `readDoorTiming`, `setDoorTiming` | `U` | V2.8 |
| `readSettings`, `applySettings` | `P` | Base |
| `runDiagnostic` | `T` | Maintenance opt-in |
| `reset` | `R` | Maintenance opt-in |

Maintenance operations are disabled by default. Set `maintenanceOperationsEnabled = true` only in service tooling where reset and actuator tests are intentionally available.

## V2.5 and V2.8 compatibility

| Area | V2.5 | V2.8 | SDK behavior |
| --- | --- | --- | --- |
| Framing, sequence, retry, CRC | Documented raw little-endian fields | Same | One strict frame codec for both revisions |
| Passage result | Seven direction-neutral values (`0`–`6`) | Expanded directional values (`0`–`9`, `@`) | Revision-specific typed result mapping |
| Physical sensor bits | Active-low | Active-low | A cleared bit is reported as active; fault bits remain active-high |
| Status length | 23 base, 27 with UPS | 23 base; TCU adds six and UPS adds four bytes | Safely accepts and validates 23/27/29/33 bytes |
| Door timing `U/1102` | Not available | Available | Capability and transaction rejected on V2.5 |
| Standby `U/2402` | Not available | Available on Kolkata profile | Capability and transaction rejected on V2.5 |
| Return-cup lamp diagnostic | Not available | Available with TCU | Rejected unless V2.8 and TCU are selected |
| Offset-hex values | `0x30..0x3F` nibbles | Same; examples include `0x3A` for ten | Dedicated offset-nibble codec, never ASCII `A..F` |

## Wire audit

The implementation is checked against the V2.5 and V2.8 GCU interface specifications. The table below records the
byte-level contract enforced by the adapter; offsets are zero-based within response sub-data after the command byte and
two-byte error code.

| Command | Request data | Successful response validation |
| --- | --- | --- |
| `V` | none | exactly five decimal version characters in `XX.YY` form |
| `A` | direction, or India direction/lamp/count | acknowledgement only; India counts are `01`–`99`, invalid tickets send `00` |
| `E` | ASCII `0`/`1` | acknowledgement only |
| `I`, `R`, `C` | none | acknowledgement only |
| `P` | selector plus complete 12-byte settings block for writes | selector plus exactly 12 validated setting bytes for reads |
| `S` | none | validated 23-byte base status with an observed four-byte UPS and/or six-byte TCU suffix |
| `T` | test group and documented action | acknowledgement only; colors and actuator actions are group-specific |
| `H` | none | exactly 12 offset-nibble sensor and sensor-error bytes |
| `D` | one `0x30`–`0x3F` pass-mode byte | acknowledgement only; mechanism/site/door-mode rules are checked before writing |
| `G` | ASCII region `1`–`6` for SectorDoor or `1`–`3` for SwingDoor | acknowledgement only |
| `X` | selector and optional `yyMMddHHmmss` | selector plus exactly 12 valid date/time digits for reads |
| `Y` | two offset-hex nibbles (`0x30..0x3F`), in ten-second units | acknowledgement only |
| `U/2402` | selector, fixed extension ID, timeout, and pass mode | V2.8 only; exact selector/timeout/mode response; Kolkata profiles only |
| `U/1102` | selector, fixed extension ID, and two 0.1-second delays | V2.8 only; exact selector and two delay values in the documented `0`–`10` range |

The fixed `S` block is decoded as follows:

| Offset | Size | Field | Accepted representation |
| ---: | ---: | --- | --- |
| 0 | 1 | pass mode | `0x30`–`0x3F` |
| 1 | 2 | entry count | decimal `00`–`99` |
| 3 | 2 | exit count | decimal `00`–`99` |
| 5 | 1 | passage result | V2.5 `0x30`–`0x36`; V2.8 `0x30`–`0x39` or `0x40` |
| 6 | 1 | entry error | `0x30`, `0x33`, `0x35`, or `0x39` |
| 7 | 1 | exit error | `0x30`, `0x33`, `0x35`, or `0x39` |
| 8 | 1 | door faults | base `0x40` plus four fault bits |
| 9 | 1 | occupied zones | base `0x80` plus seven zone bits |
| 10 | 3 | door switches | each byte `0x40`–`0x4F` |
| 13 | 8 | controller inputs | each byte `0x30`–`0x3F` |
| 21 | 1 | emergency source | `0x30`–`0x33` |
| 22 | 1 | sensor/child-sensor error | `0x30`–`0x32` |
| 23 | 4 | optional UPS | raw online/battery bits plus decimal `00`–`99` or `FF` charge |
| 23 or 27 | 6 | optional TCU | two decimal counters and return-cup state `00`/`01` |

Puloon profiles are rejected before connection when they request a FLAP mechanism, SwingDoor normal-open mode, UPS or
TCU outside India, or child sensors outside China. `GateSdk.support(config)` uses the same validation, so applications
cannot accidentally render options that the configured hardware cannot execute.

Document inconsistencies are handled explicitly. The DateTime response diagram identifies command `P` even
though the command list and request use `X`, so responses using either byte are accepted. The open/close-delay examples
say “10 seconds,” but their stated 0.1-second unit and maximum are one second; the implementation follows the stated
range and unit (`0`–`1000 ms`, in `100 ms` steps). V2.8's base status length text does not account for its documented
six-byte TCU suffix, so the decoder derives suffix presence from the received, validated 23/27/29/33-byte length instead
of trusting that contradictory total.

## Status and reconnect behavior

`connect()` reports success only after the serial port opens and a valid `S` status response is decoded. The SDK then polls status at the configured interval; Puloon requires at least 101 milliseconds. Set `statusPollInterval = null` to disable background polling and call `refreshStatus()` explicitly.

Read-only requests may be retried according to `readRetries`. Passage, emergency, reset, diagnostics, clock, mode, timing, and settings writes are never retried or replayed after a reconnect.

If a status response violates the selected revision, the protocol error written to the application log includes the field name, zero-based
payload offset, received byte, accepted range, payload length, and complete hexadecimal status payload. The session also
records per-attempt timeouts, retry/fail decisions, uncorrelated response command/sequence/retry metadata, and truncated
malformed-frame hex. These diagnostics make firmware, cabling, noise, and correlation analysis possible without enabling
unrestricted valid-frame logging.

## Settings

`readSettings()` returns the exact 12-byte `P` block as typed values: no-entry timeout, normal-open mode, hurry-up level, tag-timeout reference, tailing sensitivity, buzzer timeout, optional safety-region timeout, and child-detection level. The Puloon `P` write is a complete settings block, so `applySettings()` must receive exactly one value for every supported subtype. Read the current settings, replace the values to change, and write the complete resulting set. Invalid ranges and incomplete blocks return `GateError.InvalidRequest` before transport I/O.

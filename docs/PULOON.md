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
        ),
    ),
)
```

When parameters are omitted, Puloon uses 57,600 baud, 8 data bits, one stop bit, and no parity. A caller may supply explicit `SerialParameters` when its controller is configured differently.

Puloon GCU supports `SECTOR` and `SWING` mechanisms; `FLAP` is rejected by the factory. The hardware profile matters because the protocol exposes some features only for particular sites, mechanisms, normal-open state, or installed modules. Configure the actual device; do not select a profile only to enable a capability. Use `GateSdk.support(config)` before connection to obtain the exact capabilities, pass modes, safety regions, and sensor identifiers for a profile.

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
| `readStandbyPolicy`, `setStandbyPolicy` | `U` | Kolkata profile |
| `readDoorTiming`, `setDoorTiming` | `U` | Base |
| `readSettings`, `applySettings` | `P` | Base |
| `runDiagnostic` | `T` | Maintenance opt-in |
| `reset` | `R` | Maintenance opt-in |

Maintenance operations are disabled by default. Set `maintenanceOperationsEnabled = true` only in service tooling where reset and actuator tests are intentionally available.

## Status and reconnect behavior

`connect()` reports success only after the serial port opens and a valid `S` status response is decoded. The SDK then polls status at the configured interval; Puloon requires at least 101 milliseconds. Set `statusPollInterval = null` to disable background polling and call `refreshStatus()` explicitly.

Read-only requests may be retried according to `readRetries`. Passage, emergency, reset, diagnostics, clock, mode, timing, and settings writes are never retried or replayed after a reconnect.

## Settings

`readSettings()` returns the exact 12-byte `P` block as typed values: no-entry timeout, normal-open mode, hurry-up level, tag-timeout reference, tailing sensitivity, buzzer timeout, optional safety-region timeout, and child-detection level. The Puloon `P` write is a complete settings block, so `applySettings()` must receive exactly one value for every supported subtype. Read the current settings, replace the values to change, and write the complete resulting set. Invalid ranges and incomplete blocks return `GateError.InvalidRequest` before transport I/O.

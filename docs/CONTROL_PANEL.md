# AFC Gate Control Panel

The `control-panel` module is a hardware-only Compose Desktop operations console
built on the public `com.qurkos.gate.sdk` API. It is intentionally a
separate application module: the core SDK stays UI-free and can still be used
from server, desktop, or embedded JVM applications.

## Run locally

Requirements are JDK 17 or newer and a desktop session.

```bash
./gradlew :control-panel:run
```

Create the native application image on the current host with:

```bash
./gradlew :control-panel:createDistributable
```

The configured distribution targets are DMG, MSI, and DEB. Each installer must
be built on its matching host operating system. Tagged releases build all three
installers in GitHub Actions and attach them together with the compiled
`afc-gate-control-panel-VERSION.jar` and SDK artifacts.

Installer integration is platform-native:

| Platform | Installer behavior |
|---|---|
| Windows | Machine-wide MSI with directory chooser, Start-menu entry, desktop shortcut, branded icon, and stable upgrade identity |
| Linux | Debian package with a Utilities application-menu shortcut, branded icon, maintainer metadata, and clean package removal |
| macOS | DMG containing a branded `.app` with stable bundle ID, Applications/Launchpad integration, Dock name, and Utilities category |

macOS intentionally does not create a desktop alias: installing the `.app` in
`/Applications` makes it available through Finder, Spotlight, and Launchpad,
which is the standard macOS application model.

## Use as a compiled JAR

The control panel is intentionally not published to a Maven repository. Copy
the release JAR into the consuming application's `libs` directory and add it as
a file dependency:

```kotlin
dependencies {
    implementation(files("libs/afc-gate-control-panel-3.0.0.jar"))
}
```

The JAR contains the compiled control-panel classes and Compose resources. The
consuming application remains responsible for its Compose Desktop runtime and
the `com.qurkos.afc:afc-gate-sdk:3.0.0` dependency; file dependencies do not
carry Maven transitive metadata.

## Persistent application logs

The control panel writes runtime/OS details, detected ports, the complete non-secret gate profile, resolved support,
connection and reconnect transitions, correlated semantic TX/RX traffic, per-attempt timeouts, uncorrelated-frame
metadata, changed status snapshots, protocol validation failures, and uncaught exceptions to rotating UTF-8 log files.
Valid raw frames, credentials, ticket contents, and passenger identifiers are not logged. A rejected status payload is
recorded in full hexadecimal form; other rejected frames are capped at 128 bytes so CRC/framing faults can be diagnosed
without allowing unbounded log records. Each file is limited to 5 MiB and the latest seven files are retained.

| Platform | Default log directory |
|---|---|
| Windows | `%LOCALAPPDATA%\\Qurkos\\AFC Gate Control Panel\\logs` |
| Linux | `${XDG_STATE_HOME:-~/.local/state}/afc-gate-control-panel/logs` |
| macOS | `~/Library/Logs/AFC Gate Control Panel` |

Use **Event Log → Open log folder** to open the active location. Engineering
and automated-test environments can override it with the JVM property
`-Dafc.gate.log.dir=/path/to/logs`.

## Hardware operation

The application does not connect automatically. Review the serial port,
baud rate, response timeout, and polling interval under Configuration, then use
the connection control in the persistent header. The controller creates a
Puloon `Gate` with the public factory and routes actions as follows:

Select the actual protocol revision, mechanism, Standard/BLDC controller variant, site, and installed modules. Impossible
combinations are rejected before connection. After the status handshake, the panel reads the controller's complete
settings block and uses it as the editable baseline; it does not overwrite hardware from display defaults.

| Control-panel action | SDK operation |
|---|---|
| Allow Entry | `Gate.allowEntry()` |
| Allow Exit | `Gate.allowExit()` |
| Reject Passage | `Gate.rejectPassage(GateDirection.ENTRY)` |
| Emergency Release | `Gate.setEmergency(true)` |
| Clear Emergency Release | `Gate.setEmergency(false)` |
| Connect / Disconnect | `Gate.connect()` / `Gate.disconnect()` |
| Door diagnostic | `Gate.runDiagnostic(GateDiagnostic.Door(...))` |
| End-display diagnostic | `Gate.runDiagnostic(GateDiagnostic.EndDisplay(...))` |
| Direction-indicator diagnostic | `Gate.runDiagnostic(GateDiagnostic.Indicator(...))` |
| Buzzer diagnostic | `Gate.runDiagnostic(GateDiagnostic.Buzzer(index, enabled))` |

Commands are disabled while an operation is in flight. A missing connection,
unsupported capability, timeout, protocol rejection, or transport failure is
shown as a typed operational message rather than thrown through the UI.

## Safety boundaries

- Hardware commands require an explicit connection.
- Connected state requires a valid GCU status handshake, not only an open port.
- Unsupported controls and finite choices are omitted using `GateSdk.support`.
- The UI contains no simulator, fake transport, or simulated connected state.
- Motion begins only after a successful SDK result or validated status update.
- Emergency activation, controller reset, and counter clear/door-close require a continuous three-second hold.
- **Run read-only** excludes every maintenance actuator, lamp, buzzer, reset, and counter-clear operation.
- Maintenance diagnostics remain capability-gated and require the explicit
  maintenance setting in the hardware configuration.
- Automatic SDK reconnection never replays entry, exit, emergency, reset, or
  configuration commands.
- A timed-out state-changing command has an unknown physical outcome. Operators
  should inspect status before issuing another actuator command.

This application is an engineering and commissioning tool. A deployment must
still implement the site's authorization, operator identity, audit retention,
physical emergency circuitry, access control, and regulatory requirements.

## Source layout

```text
control-panel/src/
├── main/
│   ├── composeResources/drawable/  Gate render assets
│   └── kotlin/com/qurkos/gate/controlpanel/
│       ├── app/                     Hardware state owner and SDK routing
│       └── ui/
│           ├── components/          Shared operational components
│           ├── model/               Immutable UI state and callbacks
│           ├── screens/             Five top-level screens
│           └── theme/               Material colors, type, and theme
└── test/kotlin/                     Fake-Gate contract and controller tests
```

The UI consumes immutable state and emits callbacks. It does not call the SDK
directly. `ControlPanelController` is the single routing boundary,
and `GateTwin` is a reusable state renderer shared by Live Control and Sensors.

## Verification

```bash
./gradlew clean check :control-panel:check
```

The module compiles with warnings as errors. Controller tests inject a fake
implementation of the public `Gate` contract and cover connection enforcement,
identity and status mapping, all 18 base SectorDoor sensors plus profile-specific optional inputs, entry, exit, rejection, emergency,
reset, diagnostics, failures, and lifecycle behavior without opening a port.
Real-device HIL checks remain an explicit commissioning activity.

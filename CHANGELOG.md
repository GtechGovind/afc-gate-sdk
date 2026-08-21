# Changelog

All notable changes are recorded here. The project follows semantic versioning.

## 3.0.0 - 2026-08-21

This release intentionally changes Puloon framing and hardware-profile semantics to match both shipped PGcuTp vendor
tools. Recompile consumers and explicitly select `controllerVariant = BLDC` only for BLDC SectorDoor controllers.

- Replaced the delimiter-unsafe raw 16-bit frame counter with the deployed vendor-tool encoding: one unsigned-byte
  counter represented by two `0x30..0x3F` nibbles and one offset retry nibble. Added vectors for `0x0A`, `0x0D`, and
  `0xFF`, fragmentation, correlation, retry, and wrap at 256.
- Added `GateControllerVariant` so BLDC-only invalid-ticket behavior is never advertised to standard SectorDoor or
  SwingDoor controllers.
- Enforced V2.8 SectorDoor for TCU profiles, corrected TCU sensor IDs to 21–24, and rejected V2.8 token suffixes for V2.5.
- Made TCU status reads non-retryable because the controller clears token counters after responding; a lost response can
  no longer be hidden by a retry that returns zero counters.
- Preserved general versus child-sensor fault categories, aggregate faults from unmapped raw bits, and the uncalibrated
  return-cup signal without claiming an undocumented occupied polarity.
- Validated RTC years, whole-second standby values, and door-timing responses symmetrically with write limits.
- Prevented hidden reconnect after an initial open failure, serialized disconnect against in-flight transactions, and
  guaranteed failed/cancelled JVM opens release acquired native handles.
- Loaded the physical controller settings before control-panel editing and added profile-exact BLDC, revision, TCU, and
  Swing safety-region preflight validation.
- Hardened releases so tags must already be reachable from `main`, checksum files use exact asset basenames, and native
  installer filenames are stable across GitHub upload normalization.
- Constrained vulnerable Dokka/ktlint-only Jackson, jsoup, and Logback transitives. Disabled Gradle build-output caching
  to mitigate Kotlin's build-cache deserialization advisory without forcing a prerelease Kotlin runtime on consumers.

## 2.0.0 - 2026-08-21

This release adds `protocolRevision` to `GateHardwareProfile`. Recompile consumers against 2.0.0 and select `V2_5` for
legacy controllers; source using default or named arguments otherwise remains unchanged.

- Added an explicit Puloon `V2_5`/`V2_8` hardware-profile revision and a matching control-panel selector so response
  semantics and capabilities follow the connected firmware rather than an implicit assumption.
- Corrected physical sensor activity to active-low while preserving active-high fault bits, matching both specifications
  and the vendor test tool.
- Corrected all offset-hex payload fields (`0x30..0x3F`, including A-F values) for settings and UPS shutdown commands.
- Added direction-neutral V2.5 passage-result values while retaining the expanded direction-specific V2.8 result map.
- Accepted every valid 23/27/29/33-byte status layout and decoded observed UPS/TCU suffixes safely, allowing mixed firmware
  deployments without weakening field validation.
- Restricted V2.8-only door-timing, standby, and return-cup commands before serial transmission and explicitly disabled
  hardware flow control, DTR, and RTS at the JVM boundary.
- Expanded regression coverage for both protocol revisions, boundary nibbles, optional status suffixes, capability
  rejection, sensor polarity, and control-panel validation.

## 1.0.3 - 2026-08-21

- Audited every V2.8 Puloon command, response, field offset, numeric representation, profile restriction, and documented
  error code against the SDK implementation.
- Added actionable rejected-status diagnostics with the exact field, payload offset, received byte, expected range,
  payload length, and full hexadecimal status block.
- Added deep bounded diagnostic logs for runtime identity, port discovery, hardware/serial configuration, resolved support,
  lifecycle and reconnect transitions, changed status snapshots, read-attempt timeouts, uncorrelated responses, and
  malformed-frame metadata/hex.
- Enforced profile-exact base, UPS, and TCU status lengths and validated emergency, sensor-error, UPS state/charge, token
  counters, and return-cup fields before publishing status.
- Rejected undocumented Puloon hardware profiles and unsupported standby pass modes before serial I/O so unsupported
  controls remain unavailable to callers and the control panel.
- Tightened RTC and `U` extension selectors and lengths while preserving compatibility with the specification's documented
  DateTime response-command inconsistency.
- Added CI enforcement requiring every merge request to increment the semantic version and add its matching changelog
  section.

## 1.0.2 - 2026-08-20

- Added Windows Start-menu and desktop shortcuts, a stable MSI upgrade identity, and configurable machine-wide
  installation.
- Added a Linux desktop/application-menu shortcut and complete Debian application metadata.
- Added a stable macOS bundle identity, utilities category, minimum OS version, Dock name, and native application icon.
- Added branded Windows, Linux, and macOS package icons generated from one maintained vector source.
- Added persistent UTF-8 operational and semantic traffic logs with 5 MiB rotation, seven-file retention, standard
  per-platform storage locations, uncaught-exception capture, and an in-app Open log folder action.

## 1.0.1 - 2026-08-20

- Aligned Puloon GCU commands, settings, status decoding, diagnostics, errors, and sensor handling with interface
  specification V2.8.
- Added hardware-profile support discovery so only valid mechanisms, pass modes, settings, sensors, and diagnostics are
  exposed.
- Required a valid status handshake before reporting a green connected state and added complete UPS, TCU, passage,
  occupancy, door-fault, switch, and emergency telemetry.
- Updated the control panel with profile-aware controls and distinct red disconnected, green connected, and blue
  emergency-release gate imagery.

## 1.0.0 - 2026-08-20

- Introduced the unified `com.qurkos.gate.sdk.Gate` API and Kotlin Multiplatform JVM publication.
- Added the complete Puloon GCU adapter, typed models, shared serial session, safe-read retries, polling, and reconnect.
- Added bounded buffers, strict payload validation, defensive copies, concurrent operation serialization, and complete
  settings validation.
- Added strict KDoc/Dokka, formatting, static analysis, ABI validation, coverage enforcement, dependency governance, CI,
  CodeQL, dependency review, release automation, and operational documentation.
- Added the hardware-only Compose Desktop control panel with live operations, sensor telemetry, typed configuration,
  diagnostics, event export, safety holds, and native DMG, MSI, and DEB packaging.
- Added correlated semantic command and response events for live TX/RX monitoring without exposing raw protocol access.

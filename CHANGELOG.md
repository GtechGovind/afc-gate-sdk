# Changelog

All notable changes are recorded here. The project follows semantic versioning.

## 1.0.3 - 2026-08-21

- Audited every V2.8 Puloon command, response, field offset, numeric representation, profile restriction, and documented
  error code against the SDK implementation.
- Added actionable rejected-status diagnostics with the exact field, payload offset, received byte, expected range,
  payload length, and full hexadecimal status block.
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

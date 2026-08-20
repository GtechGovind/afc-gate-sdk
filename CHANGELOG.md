# Changelog

All notable changes are recorded here. The project follows semantic versioning.

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

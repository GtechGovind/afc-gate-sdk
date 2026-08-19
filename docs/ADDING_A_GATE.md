# Adding a gate vendor

New gate implementations use the existing `Gate` interface and shared serial session. Do not expose a vendor-specific driver, command byte, frame, or serial library type.

## Implementation checklist

1. Place the adapter and its codecs under `src/commonMain/kotlin/com/qurkos/gate/sdk/internal/<vendor>`.
2. Implement `GateProtocolAdapter` with the vendor descriptor, default serial parameters, minimum safe polling interval, capability set, streaming decoder, and typed operation mapping.
3. Map each supported `GateOperation` to an immutable `SerialTransaction`. Mark a transaction idempotent only when repeating it cannot change device state.
4. Correlate a response using the protocol's sequence, command, address, or equivalent identifiers. Reject malformed and unrelated frames.
5. Normalize replies into `GateResponse`; do not leak vendor wire values into public models.
6. Add the vendor to the selection in `GateSdk.create` only after the adapter is complete.

An unsupported common operation belongs in neither a fake implementation nor a raw escape hatch. Omit its capability; `SerialGateController` will return `GateError.UnsupportedCapability` without a serial write.

## Codec rules

- Accept and return defensive copies of mutable byte arrays.
- Support fragmented and coalesced input in the streaming decoder.
- Validate length, checksum, fields, and legal values before producing a response.
- Keep framing separate from payload translation when each has meaningful independent behavior.
- Use Kotlin APIs in `commonMain`. Place unavoidable Java integration in `jvmMain`.
- Keep documented wire constants internal.

## Required tests

- Golden request and response frames from the vendor specification
- Checksum/CRC rejection and malformed length handling
- Fragmented frames, multiple frames in one read, and unrelated responses
- Every supported `GateOperation`, including boundary values
- Capability rejection before transport writes
- Safe-read retry and state-changing-command non-retry
- Timeout, cancellation, disconnect, and automatic reconnect
- Verification that reconnect never replays entry, exit, emergency, reset, diagnostics, or configuration writes

Run `./gradlew clean check` before publishing. Physical hardware tests should be separately tagged and excluded from normal `check`.

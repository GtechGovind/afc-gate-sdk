# Architecture

## Design boundary

Applications see one package, `com.qurkos.gate.sdk`, and one device interface, `Gate`. Vendor commands, wire values, frames, CRC handling, response correlation, and jSerialComm types are internal.

```text
Application
    |
    v
Gate / GateSdk / vendor-neutral models
    |
    v
SerialGateController
    |                       capability check + operation mapping
    v
GateProtocolAdapter         Puloon today; Gunnebo and Indra later
    |
    v
SerialSession               serialization, timeout, retry, polling, reconnect
    |
    v
SerialTransport             common boundary
    |
    v
JSerialCommTransport        JVM-only Java integration
```

## Responsibilities

`Gate` defines lifecycle, passage authorization, emergency control, status, mode, sensors, counters, clock, standby, door timing, UPS, diagnostics, reset, and typed settings. It uses immutable models, `GateResult`, coroutines, `Flow`, and `StateFlow`.

`SerialGateController` is the only implementation of `Gate`. It rejects unsupported capabilities before creating or writing a protocol request, updates normalized status, and delegates all wire translation.

`GateProtocolAdapter` owns vendor defaults, capabilities, operation encoding, streaming frame decoding, response correlation, and response conversion. An adapter must not own a serial port or launch unstructured coroutines.

`SerialSession` owns the connection lifecycle and allows only one request on the wire at a time. Read-only transactions may be retried after a timeout. State-changing commands are sent once. After reconnecting, the session resumes status monitoring without replaying a command.

`SerialTransport` is a common source-set boundary. jSerialComm and required Java APIs exist only in `jvmMain`, where blocking I/O runs on `Dispatchers.IO`.

## Source layout

```text
src/commonMain/kotlin/com/qurkos/gate/sdk/
├── Gate.kt
├── GateSdk.kt
├── GateModels.kt
├── GateConfiguration.kt
├── GateResult.kt
└── internal/
    ├── GateProtocolAdapter.kt
    ├── SerialGateController.kt
    ├── SerialSession.kt
    ├── puloon/
    ├── gunebo/
    └── indra/

src/jvmMain/kotlin/com/qurkos/gate/sdk/internal/
├── JvmSerialPlatform.kt
└── jvm/JSerialCommTransport.kt
```

Tests mirror production packages. Protocol tests use golden frames and an in-memory serial transport; normal builds do not require physical gate hardware.

## Error and cancellation policy

Expected failures are values represented by `GateError`, not vendor exceptions. Public operations do not expose protocol bytes. Coroutine cancellation is rethrown and is never converted into a transport failure.

## Extension rules

Adding a vendor changes an internal adapter and the `GateSdk` selection branch. It must not create a second public interface or vendor-specific public command API. If a feature is meaningful across gates, add a vendor-neutral model and capability; otherwise leave it unsupported.

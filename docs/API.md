# Module AFC Gate SDK

The AFC Gate SDK provides one coroutine-first API for serial automatic-fare-collection gates. Create a configured device
with `GateSdk.create`, acquire its serial port with `Gate.connect`, invoke typed commands through `Gate`, observe lifecycle
and status flows, and release the port with `Gate.disconnect`.

Expected transport, protocol, device, validation, capability, and vendor failures are returned as `GateResult` values.
Coroutine cancellation remains cancellation. Implementations serialize commands, retry only idempotent reads, bound
incoming buffers, validate complete frames before decoding them, and never replay state-changing operations after reconnect.

# Package com.qurkos.gate.sdk

Public, vendor-neutral SDK surface. This package contains the `Gate` contract, factory, configuration, immutable normalized
models, capabilities, events, and typed result/error hierarchy. Consumer code should import only this package.

# Package com.qurkos.gate.sdk.internal

Shared implementation machinery. `SerialGateController` implements the public contract, `SerialSession` owns serial
lifecycle and reliability policy, and `GateProtocolAdapter` defines the internal vendor extension point. These declarations
are documented for SDK maintainers but are not source- or binary-compatible consumer API.

# Package com.qurkos.gate.sdk.internal.puloon

Puloon GCU protocol implementation: operation mapping, frame/CRC codec, streaming decoder, payload conversion, fixed-width
settings codec, capability derivation, and response correlation. Wire values remain internal.

# Package com.qurkos.gate.sdk.internal.jvm

JVM-only jSerialComm integration. Blocking native calls are isolated on `Dispatchers.IO`; common source sets contain no
jSerialComm or other Java serial APIs.

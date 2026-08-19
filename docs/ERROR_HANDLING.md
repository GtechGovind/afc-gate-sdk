# Error-handling contract

Every expected operational failure is returned as `GateResult.Failure`. Coroutine cancellation and programming errors such
as constructing an invalid value remain exceptions.

| Error | Meaning | Recommended action |
| --- | --- | --- |
| `NotConnected` | No connected session exists when a command reaches the wire layer. | Wait for `CONNECTED` or call `connect()`. |
| `Timeout` | No correlated valid response arrived before the configured deadline. | Retry only a read. For writes, query state before deciding. |
| `Transport` | Port discovery, open, read, write, or close failed. | Check connection state, port ownership, permissions, cabling, and OS logs. |
| `Protocol` | A frame or payload was malformed or had an unexpected response type. | Record the warning/error, inspect firmware compatibility, and avoid blind retries of writes. |
| `Device` | The controller returned a protocol error code. | Handle the known code; escalate unknown codes with firmware and command context. |
| `InvalidRequest` | The adapter rejected an invalid value or incomplete block before writing. | Correct configuration or input; retrying unchanged input cannot help. |
| `UnsupportedCapability` | The hardware profile does not support the requested operation. | Disable that feature or correct the physical profile. |
| `UnsupportedVendor` | No implementation is included for the selected vendor. | Add/upgrade the adapter or select the correct vendor. |

Use an exhaustive `when` so newly introduced error variants produce a compiler-visible integration change:

```kotlin
fun classify(error: GateError): Recovery =
    when (error) {
        GateError.NotConnected -> Recovery.RECONNECT
        is GateError.Timeout -> Recovery.VERIFY_DEVICE_STATE
        is GateError.Transport -> Recovery.RECONNECT
        is GateError.Protocol -> Recovery.QUARANTINE_AND_ALERT
        is GateError.Device -> Recovery.DEVICE_SPECIFIC
        is GateError.InvalidRequest -> Recovery.FIX_INPUT
        is GateError.UnsupportedCapability -> Recovery.DISABLE_FEATURE
        is GateError.UnsupportedVendor -> Recovery.UPGRADE_OR_RECONFIGURE
    }
```

## Cancellation

The SDK rethrows `CancellationException`. This ensures a cancelled request does not continue retrying and a cancelled
application scope does not masquerade as a transport outage. Cleanup belongs in `finally` or another non-cancellable
shutdown policy chosen by the host application.

## Retry safety

Only adapter transactions explicitly marked idempotent are retried. Current examples include firmware, status, sensors,
clock reads, and settings reads. Passage authorization, emergency, mode, counters, clock writes, timing/configuration,
diagnostics, and reset are written once. Reconnect never retains and replays a command.

A timeout does not prove that the controller failed to execute a request. For any state-changing timeout, reconcile by
reading status/configuration or by following the station's operational recovery procedure.

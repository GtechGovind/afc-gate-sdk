# Application integration

## Ownership rule

Create one `Gate` for one physical serial controller. The application component that owns the port also owns collection of
its flows and must call `disconnect()` during shutdown. Do not create multiple SDK instances for the same operating-system
port.

```kotlin
val gate = when (val created = GateSdk.create(config)) {
    is GateResult.Success -> created.value
    is GateResult.Failure -> return reportConfigurationFailure(created.error)
}

try {
    when (val connected = gate.connect()) {
        is GateResult.Success -> runGateLoop(gate)
        is GateResult.Failure -> reportConnectionFailure(connected.error)
    }
} finally {
    gate.disconnect()
}
```

`connect()` and `disconnect()` are idempotent. An intentionally disconnected instance can be connected again. Do not wrap
SDK calls in `runCatching`, because that can accidentally convert coroutine cancellation into an ordinary failure.

## Commands

All calls are safe from concurrent coroutines. The SDK serializes transaction allocation and wire access; callers do not
need an application mutex.

```kotlin
suspend fun admitPassenger(gate: Gate, direction: GateDirection): GateResult<Unit> =
    when (direction) {
        GateDirection.ENTRY -> gate.allowEntry()
        GateDirection.EXIT -> gate.allowExit()
    }
```

Use `capabilities` to disable unavailable UI actions, but still handle `UnsupportedCapability`. Capability checks are also
performed immediately before transaction creation, and an unsupported call produces no serial write.

## State and events

`connectionState` always has a value. `status` is `null` until a valid status response is decoded and becomes `null` again
after an intentional disconnect. `events` contains observational transitions and warnings; it is not a replacement for the
`GateResult` returned by a command.

```kotlin
val stateJob = scope.launch {
    gate.connectionState.collect { state -> healthReporter.connection(state) }
}
val statusJob = scope.launch {
    gate.status.collect { status -> status?.let(healthReporter::status) }
}
val eventJob = scope.launch {
    gate.events.collect { event -> auditEvent(event) }
}
```

Collectors should start before `connect()` if the application must observe the initial transition. Use an application-owned
structured scope and cancel those jobs when the gate-owning component stops.

### Live command traffic

The same `events` flow publishes `GateEvent.CommandSent` and `GateEvent.ResponseReceived` around every serialized SDK
operation, including connection, background status polling, diagnostics, settings, and passage commands. Correlate the two
events by `sequence`; `ResponseReceived` includes the normalized `outcome`, elapsed duration, and a safe semantic detail.

```kotlin
gate.events.collect { event ->
    when (event) {
        is GateEvent.CommandSent -> trafficLog.tx(event.sequence, event.command, event.detail)
        is GateEvent.ResponseReceived -> trafficLog.rx(
            sequence = event.sequence,
            command = event.command,
            outcome = event.outcome,
            elapsed = event.elapsed,
            detail = event.detail,
        )
        else -> Unit
    }
}
```

The feed is observational and deliberately contains no raw frame bytes or vendor command values. Applications must continue
to invoke operations through `Gate` and use the returned `GateResult` as the authoritative command result. Bound any UI or
in-memory history and persist traffic only under the application's own retention and access-control policy.

## Status polling

Background status polling is enabled by default. Choose an interval that meets application freshness requirements without
overloading the controller. The factory rejects an interval below the adapter's safe minimum. Set the interval to `null`
for applications that schedule explicit `refreshStatus()` calls.

Polling shares the same serialized wire queue as application commands. A long response timeout therefore also delays later
commands; select a timeout based on measured hardware behavior rather than making it arbitrarily large.

## Updating settings safely

Puloon writes a complete settings block. First read the current block, replace only the intended subtype, and write one
value of every subtype back. The adapter rejects missing, duplicate, non-finite, or out-of-range values before I/O.

```kotlin
val current = when (val result = gate.readSettings()) {
    is GateResult.Success -> result.value
    is GateResult.Failure -> return result
}
val updated = current.filterNot { it is GateSetting.PassageTimeout }.toSet() +
    GateSetting.PassageTimeout(30.seconds)
return gate.applySettings(updated)
```

Treat a settings write timeout as an unknown device outcome. Read the settings back before deciding whether another write is
required; the SDK intentionally does not retry the write.

## Shutdown and application restarts

During graceful shutdown, stop issuing new commands, call `disconnect()`, and record a close failure for operations staff.
After an ungraceful process restart, query status and configuration before assuming the controller's state. Never infer that
a timed-out state-changing command was rejected—the command may have reached the controller while its acknowledgement was
lost.

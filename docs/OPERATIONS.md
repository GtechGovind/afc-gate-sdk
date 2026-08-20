# Operations and troubleshooting

## Runtime requirements

- JVM 17 or newer
- Exclusive access to the configured serial port
- OS permissions for the service account
- A hardware profile matching the physical mechanism, regional firmware, and installed modules
- On JVMs that enforce native-access warnings, pass `--enable-native-access=com.fazecast.jSerialComm` when jSerialComm is a
  named module, or the appropriate `ALL-UNNAMED` setting when deployed on the classpath

On Linux, the service user typically needs membership in the distribution's serial-port group such as `dialout` or `uucp`.
Use the least privilege required for the specific device; do not run the gate process as root.

## Startup checklist

1. Enumerate ports for diagnostics, but select the production port from controlled configuration rather than description
   matching.
2. Create the gate and reject any configuration failure.
3. Start lifecycle/event collectors.
4. Connect and wait for `CONNECTED`.
5. Read firmware and status; compare firmware with the deployment's approved compatibility matrix.
6. Enable passenger commands only after a valid status snapshot.

## Recommended telemetry

Record counters for connection transitions, reconnect attempts, command success/failure by common operation and error type,
timeouts, device error codes, and protocol warnings. Record histograms for command latency and reconnect duration. Avoid
logging raw protocol frames, passenger identifiers, credentials, or unrestricted OS exception details.

Alert on repeated `RECONNECTING`/`FAILED`, sustained status staleness, rising timeouts, unknown device codes, CRC failures,
or protocol warnings after a firmware rollout. A single reconnect can be normal for a USB reset; repeated transitions
usually indicate power, cabling, driver, port ownership, or controller problems.

## Control-panel log files

The desktop control panel persists safe operational events, semantic command/response traffic, application lifecycle
messages, and uncaught exceptions. It rotates at 5 MiB and retains seven files. Operators can open the active directory
from **Event Log → Open log folder**.

- Windows: `%LOCALAPPDATA%\\Qurkos\\AFC Gate Control Panel\\logs`
- Linux: `${XDG_STATE_HOME:-~/.local/state}/afc-gate-control-panel/logs`
- macOS: `~/Library/Logs/AFC Gate Control Panel`

Collect these files with controller firmware, hardware profile, and incident time when escalating a fault. Do not add raw
protocol frames, credentials, passenger identifiers, or ticket contents to application log messages.

## Troubleshooting

### Port cannot be opened

- Confirm the configured descriptor exists in `GateSdk.serialPorts()`.
- Confirm no second process or SDK instance owns it.
- Verify service-account permissions and device-node ownership.
- Check USB enumeration, cable, power, and kernel/system logs.
- Confirm the configured serial parameters or use the adapter defaults.

### Commands time out

- Verify the controller is powered and firmware matches the documented protocol.
- Check baud, data bits, stop bits, and parity.
- Increase timeout only after measuring actual response latency.
- Inspect `ProtocolWarning` events for CRC, length, or field validation failures.
- Do not automatically retry state-changing commands at the application layer.

### Status is always null

- Confirm `connect()` succeeded.
- Confirm status polling is enabled or call `refreshStatus()`.
- Inspect command results rather than relying only on events.
- Verify the selected hardware/site profile matches the response layout.

### Reconnect loop does not recover

- Confirm `ReconnectPolicy` is not disabled.
- Ensure the OS descriptor remains stable after USB re-enumeration.
- If the descriptor changes, disconnect, rebuild configuration with the new port, and create a new gate instance.

## Maintenance mode

Diagnostics and reset are disabled by default. Enable them only in authenticated service tooling with physical-area safety
controls. Do not expose them to ordinary passenger processing or automatically run actuator diagnostics during startup.

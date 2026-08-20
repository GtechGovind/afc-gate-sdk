package com.qurkos.gate.sdk

import com.qurkos.gate.sdk.internal.SerialGateController
import com.qurkos.gate.sdk.internal.availablePlatformSerialPorts
import com.qurkos.gate.sdk.internal.createPlatformSerialTransport
import com.qurkos.gate.sdk.internal.platformSessionDispatcher
import com.qurkos.gate.sdk.internal.puloon.PuloonAdapter

/**
 * Process-wide entry point for creating vendor-neutral [Gate] instances and discovering serial ports.
 *
 * The factory performs configuration validation and adapter selection but does not open hardware. Creation is cheap and
 * side-effect free; call [Gate.connect] when the owning application component is ready to acquire the serial port.
 */
public object GateSdk {
    /**
     * Creates a gate without opening its serial connection.
     *
     * Vendor defaults are applied when [SerialConnectionConfig.parameters] is `null`. Polling intervals are checked
     * against the selected controller's minimum safe interval before an implementation is returned.
     *
     * @param config Complete immutable device configuration.
     * @return [GateResult.Success] containing the common [Gate] interface, or a typed configuration/vendor failure.
     */
    public fun create(config: GateDeviceConfig): GateResult<Gate> {
        val stableConfig =
            config.copy(
                hardware = config.hardware.copy(modules = config.hardware.modules.toSet()),
            )
        val adapter =
            when (val result = adapter(stableConfig)) {
                is GateResult.Success -> result.value
                is GateResult.Failure -> return result
            }
        val serial =
            stableConfig.serial.copy(
                parameters = stableConfig.serial.parameters ?: adapter.defaultSerialParameters,
            )
        val pollInterval = stableConfig.runtime.statusPollInterval
        if (pollInterval != null && pollInterval < adapter.minimumPollInterval) {
            return GateResult.Failure(
                GateError.InvalidRequest(
                    "${stableConfig.vendor} status polling must be at least ${adapter.minimumPollInterval}",
                ),
            )
        }
        return GateResult.Success(
            SerialGateController(
                config = stableConfig.copy(serial = serial),
                adapter = adapter,
                transport = createPlatformSerialTransport(),
                dispatcher = platformSessionDispatcher(),
            ),
        )
    }

    /**
     * Lists serial ports currently visible to the host platform.
     *
     * Discovery does not open, probe, or modify a port and therefore cannot identify a gate vendor.
     *
     * @return A snapshot of visible ports, or [GateError.Transport] when platform enumeration fails.
     */
    public fun serialPorts(): GateResult<List<SerialPortInfo>> = availablePlatformSerialPorts()

    /**
     * Resolves capabilities and finite choices without opening or probing a serial port.
     *
     * Applications should use this before rendering configuration and maintenance controls.
     */
    public fun support(config: GateDeviceConfig): GateResult<GateSupport> {
        val stableConfig = config.copy(hardware = config.hardware.copy(modules = config.hardware.modules.toSet()))
        return adapter(stableConfig).map { it.support }
    }

    private fun adapter(config: GateDeviceConfig): GateResult<com.qurkos.gate.sdk.internal.GateProtocolAdapter> =
        when (config.vendor) {
            GateVendor.PULOON -> {
                if (config.hardware.mechanism == GateMechanism.FLAP) {
                    GateResult.Failure(
                        GateError.InvalidRequest("Puloon GCU supports SectorDoor or SwingDoor, not FLAP"),
                    )
                } else {
                    GateResult.Success(PuloonAdapter(config.hardware, config.maintenanceOperationsEnabled))
                }
            }
            GateVendor.GUNNEBO,
            GateVendor.INDRA,
            -> GateResult.Failure(GateError.UnsupportedVendor(config.vendor))
        }
}

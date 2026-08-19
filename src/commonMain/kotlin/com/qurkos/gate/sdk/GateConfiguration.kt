package com.qurkos.gate.sdk

import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Stable identifier for a gate protocol implementation known to the SDK.
 *
 * Selecting a value does not guarantee that its adapter is present in the current SDK version. In that case
 * [GateSdk.create] returns [GateError.UnsupportedVendor]. Vendor identifiers are configuration values only; callers
 * continue to interact with the vendor-neutral [Gate] interface.
 */
public enum class GateVendor {
    PULOON,
    GUNNEBO,
    INDRA,
}

/**
 * Name used by the host operating system to identify a serial port.
 *
 * Examples include `COM4`, `/dev/ttyUSB0`, and `/dev/cu.usbserial-0001`.
 *
 * @property value Non-blank platform port descriptor passed unchanged to the JVM serial backend.
 * @throws IllegalArgumentException when [value] is blank.
 */
@JvmInline
public value class SerialPortName(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) {
            "Serial port name must not be blank"
        }
    }
}

/** Parity mode applied to serial words by the platform transport. */
public enum class SerialParity {
    NONE,
    ODD,
    EVEN,
    MARK,
    SPACE,
}

/** Number of stop bits applied by the platform serial transport. */
public enum class SerialStopBits {
    ONE,
    ONE_POINT_FIVE,
    TWO,
}

/**
 * Fully specified serial line parameters.
 *
 * @property baudRate Positive symbol rate, such as `57_600` for the Puloon GCU default.
 * @property dataBits Number of data bits per word, from 5 through 8.
 * @property stopBits Stop-bit configuration.
 * @property parity Parity mode.
 * @throws IllegalArgumentException when a numeric value is outside its supported transport range.
 */
public data class SerialParameters(
    public val baudRate: Int,
    public val dataBits: Int = 8,
    public val stopBits: SerialStopBits = SerialStopBits.ONE,
    public val parity: SerialParity = SerialParity.NONE,
) {
    init {
        require(baudRate > 0) { "Baud rate must be positive" }
        require(dataBits in 5..8) { "Data bits must be between 5 and 8" }
    }
}

/**
 * Serial endpoint used by one gate instance.
 *
 * @property port Platform serial-port name.
 * @property parameters Explicit line parameters, or `null` to resolve the adapter's documented defaults during
 * [GateSdk.create].
 */
public data class SerialConnectionConfig(
    public val port: SerialPortName,
    public val parameters: SerialParameters? = null,
)

/**
 * Reconnection behavior after an open or established serial session fails.
 *
 * Reconnection restores the transport and status monitoring. The SDK never replays state-changing commands.
 */
public sealed interface ReconnectPolicy {
    /** Leaves the gate failed until the caller invokes [Gate.connect] again. */
    public data object Disabled : ReconnectPolicy

    /**
     * Retries the transport indefinitely with a bounded exponential delay.
     *
     * @property initialDelay Positive finite delay before the first retry.
     * @property maximumDelay Finite upper bound for subsequent delays.
     * @property multiplier Finite growth factor greater than or equal to one.
     */
    public data class ExponentialBackoff(
        public val initialDelay: Duration = 500.milliseconds,
        public val maximumDelay: Duration = 10.seconds,
        public val multiplier: Double = 2.0,
    ) : ReconnectPolicy {
        init {
            require(initialDelay.isPositive() && initialDelay.isFinite()) {
                "Initial reconnect delay must be positive and finite"
            }
            require(maximumDelay.isFinite() && maximumDelay >= initialDelay) {
                "Maximum reconnect delay must be finite and not less than the initial delay"
            }
            require(multiplier.isFinite() && multiplier >= 1.0) {
                "Reconnect multiplier must be finite and at least 1"
            }
        }
    }
}

/**
 * Runtime reliability policy shared by every serial gate adapter.
 *
 * @property responseTimeout Maximum finite time to wait for one correlated response.
 * @property readRetries Number of retries for idempotent reads only, between 0 and 10. Writes are always attempted once.
 * @property statusPollInterval Positive finite polling interval, or `null` to disable background status reads.
 * @property reconnectPolicy Transport recovery policy.
 */
public data class GateRuntimeOptions(
    public val responseTimeout: Duration = 1.seconds,
    public val readRetries: Int = 2,
    public val statusPollInterval: Duration? = 500.milliseconds,
    public val reconnectPolicy: ReconnectPolicy = ReconnectPolicy.ExponentialBackoff(),
) {
    init {
        require(responseTimeout.isPositive() && responseTimeout.isFinite()) {
            "Response timeout must be positive and finite"
        }
        require(readRetries in 0..MAX_READ_RETRIES) {
            "Read retries must be between 0 and $MAX_READ_RETRIES"
        }
        require(statusPollInterval == null || (statusPollInterval.isPositive() && statusPollInterval.isFinite())) {
            "Status poll interval must be positive and finite when enabled"
        }
    }

    private companion object {
        const val MAX_READ_RETRIES = 10
    }
}

/** Physical barrier mechanism used to validate mechanism-specific protocol values. */
public enum class GateMechanism {
    FLAP,
    SWING,
    SECTOR,
}

/** Site profile used when a controller protocol exposes regional command variants. */
public enum class GateSite {
    GENERIC,
    INDIA,
    KOLKATA_INDIA,
    CHINA,
}

/** Optional physical modules that enable additional controller capabilities. */
public enum class GateModule {
    UPS,
    TOKEN_CONTROL_UNIT,
    CHILD_SENSORS,
}

/**
 * Hardware facts used to select safe capabilities and payload layouts.
 *
 * @property mechanism Physical barrier mechanism.
 * @property site Regional controller profile.
 * @property modules Installed optional modules. Supplying a module that is not physically installed can produce invalid
 * status interpretation and must be avoided.
 */
public data class GateHardwareProfile(
    public val mechanism: GateMechanism = GateMechanism.FLAP,
    public val site: GateSite = GateSite.GENERIC,
    public val modules: Set<GateModule> = emptySet(),
)

/**
 * Complete immutable configuration consumed by [GateSdk.create].
 *
 * @property vendor Explicit adapter selection; serial auto-detection is intentionally unsupported.
 * @property serial Port and optional line settings.
 * @property hardware Physical controller profile.
 * @property runtime Timeouts, polling, retry, and reconnect behavior.
 * @property maintenanceOperationsEnabled Opt-in for potentially disruptive reset and actuator diagnostics.
 */
public data class GateDeviceConfig(
    public val vendor: GateVendor,
    public val serial: SerialConnectionConfig,
    public val hardware: GateHardwareProfile = GateHardwareProfile(),
    public val runtime: GateRuntimeOptions = GateRuntimeOptions(),
    public val maintenanceOperationsEnabled: Boolean = false,
)

/**
 * A serial port visible to the current platform.
 *
 * @property name Stable descriptor accepted by [SerialConnectionConfig].
 * @property description Optional human-readable description reported by the platform driver.
 */
public data class SerialPortInfo(
    public val name: SerialPortName,
    public val description: String? = null,
)

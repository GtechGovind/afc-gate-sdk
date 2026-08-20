package com.qurkos.gate.sdk

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.INFINITE
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class GateConfigurationTest {
    @Test
    fun runtimeRejectsUnboundedTimeoutsAndRetryCounts() {
        assertFailsWith<IllegalArgumentException> { GateRuntimeOptions(responseTimeout = INFINITE) }
        assertFailsWith<IllegalArgumentException> { GateRuntimeOptions(statusPollInterval = INFINITE) }
        assertFailsWith<IllegalArgumentException> { GateRuntimeOptions(readRetries = 11) }
    }

    @Test
    fun reconnectPolicyRejectsNonFiniteValues() {
        assertFailsWith<IllegalArgumentException> {
            ReconnectPolicy.ExponentialBackoff(initialDelay = INFINITE)
        }
        assertFailsWith<IllegalArgumentException> {
            ReconnectPolicy.ExponentialBackoff(1.seconds, 2.seconds, Double.POSITIVE_INFINITY)
        }
        assertFailsWith<IllegalArgumentException> {
            ReconnectPolicy.ExponentialBackoff(2.seconds, 1.seconds)
        }
        ReconnectPolicy.ExponentialBackoff(1.milliseconds, 1.seconds, 1.5)
    }

    @Test
    fun factoryRejectsUnsupportedVendorAndUnsafePolling() {
        val serial = SerialConnectionConfig(SerialPortName("fake"))
        assertIs<GateResult.Failure>(GateSdk.create(GateDeviceConfig(GateVendor.GUNNEBO, serial)))
        val unsafe =
            GateDeviceConfig(
                vendor = GateVendor.PULOON,
                serial = serial,
                runtime = GateRuntimeOptions(statusPollInterval = 100.milliseconds),
            )
        assertIs<GateResult.Failure>(GateSdk.create(unsafe))
    }

    @Test
    fun factorySnapshotsMutableHardwareCollections() {
        val modules = mutableSetOf(GateModule.UPS)
        val gate =
            assertIs<GateResult.Success<Gate>>(
                GateSdk.create(
                    GateDeviceConfig(
                        vendor = GateVendor.PULOON,
                        serial = SerialConnectionConfig(SerialPortName("fake")),
                        hardware = GateHardwareProfile(site = GateSite.INDIA, modules = modules),
                    ),
                ),
            ).value

        modules.clear()

        assertTrue(GateCapability.UPS_SHUTDOWN in gate.capabilities)
    }
}

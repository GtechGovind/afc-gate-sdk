package com.qurkos.gate.sdk.puloon

import com.qurkos.gate.sdk.GateDiagnostic
import com.qurkos.gate.sdk.GateDirection
import com.qurkos.gate.sdk.GateDoorTestAction
import com.qurkos.gate.sdk.GateDoorTiming
import com.qurkos.gate.sdk.GateHardwareProfile
import com.qurkos.gate.sdk.GateLampColor
import com.qurkos.gate.sdk.GateMechanism
import com.qurkos.gate.sdk.GateModule
import com.qurkos.gate.sdk.GatePassMode
import com.qurkos.gate.sdk.GatePassageRequest
import com.qurkos.gate.sdk.GateProtocolRevision
import com.qurkos.gate.sdk.GateResult
import com.qurkos.gate.sdk.GateSafetyRegion
import com.qurkos.gate.sdk.GateSite
import com.qurkos.gate.sdk.GateStandbyPolicy
import com.qurkos.gate.sdk.internal.GateOperation
import com.qurkos.gate.sdk.internal.SerialTransaction
import com.qurkos.gate.sdk.internal.puloon.PuloonAdapter
import com.qurkos.gate.sdk.internal.puloon.PuloonFrameCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PuloonAdapterValidationTest {
    @Test
    fun mechanismSpecificSafetyRegionBoundsAreEnforced() {
        assertSuccess(adapter(GateMechanism.SWING).transaction(GateOperation.SetSafetyRegion(GateSafetyRegion(3))))
        assertFailure(adapter(GateMechanism.SWING).transaction(GateOperation.SetSafetyRegion(GateSafetyRegion(4))))
        assertSuccess(adapter(GateMechanism.SECTOR).transaction(GateOperation.SetSafetyRegion(GateSafetyRegion(6))))
        assertFailure(adapter(GateMechanism.SECTOR).transaction(GateOperation.SetSafetyRegion(GateSafetyRegion(7))))
    }

    @Test
    fun timingUpsAndStandbyBoundariesAreEnforced() {
        val adapter = adapter(GateMechanism.SECTOR)
        assertSuccess(adapter.transaction(GateOperation.SetUpsShutdownDelay(0)))
        assertSuccess(adapter.transaction(GateOperation.SetUpsShutdownDelay(2_550)))
        assertFailure(adapter.transaction(GateOperation.SetUpsShutdownDelay(11)))
        assertFailure(adapter.transaction(GateOperation.SetUpsShutdownDelay(2_560)))
        assertSuccess(
            adapter.transaction(GateOperation.SetDoorTiming(GateDoorTiming(0.milliseconds, 1_000.milliseconds))),
        )
        assertFailure(
            adapter.transaction(GateOperation.SetDoorTiming(GateDoorTiming(50.milliseconds, 1_000.milliseconds))),
        )
        assertSuccess(
            adapter.transaction(
                GateOperation.SetStandbyPolicy(
                    GateStandbyPolicy(255.seconds, GatePassMode.CONTROLLED_BOTH),
                    normalOpen = false,
                ),
            ),
        )
        val outOfServiceStandby =
            assertSuccess(
                adapter.transaction(
                    GateOperation.SetStandbyPolicy(
                        GateStandbyPolicy(20.seconds, GatePassMode.OUT_OF_SERVICE),
                        normalOpen = false,
                    ),
                ),
            )
        val standbyModeByte =
            PuloonFrameCodec
                .decode(outOfServiceStandby.encode(0))
                .payload
                .last()
                .toInt() and 0xFF
        assertEquals(0x3F, standbyModeByte)
        assertFailure(
            adapter.transaction(
                GateOperation.SetStandbyPolicy(
                    GateStandbyPolicy(256.seconds, GatePassMode.CONTROLLED_BOTH),
                    normalOpen = false,
                ),
            ),
        )
        assertFailure(
            adapter.transaction(
                GateOperation.SetStandbyPolicy(
                    GateStandbyPolicy(20.seconds, GatePassMode.FREE_ENTRY_LOCKED_EXIT_NORMAL_OPEN),
                    normalOpen = false,
                ),
            ),
        )
    }

    @Test
    fun upsUsesOffsetNibblesForHexadecimalDigits() {
        val transaction = assertSuccess(adapter(GateMechanism.SECTOR).transaction(GateOperation.SetUpsShutdownDelay(100)))
        val payload = PuloonFrameCodec.decode(transaction.encode(0)).payload

        assertEquals(listOf(0x59, 0x30, 0x3A), payload.map { it.toInt() and 0xFF })
    }

    @Test
    fun v28ExtensionsAreRejectedForV25BeforeSerialTransmission() {
        val legacy = adapter(GateMechanism.SECTOR, revision = GateProtocolRevision.V2_5, maintenance = true)

        assertTrue(com.qurkos.gate.sdk.GateCapability.DOOR_TIMING !in legacy.capabilities)
        assertTrue(com.qurkos.gate.sdk.GateCapability.STANDBY !in legacy.capabilities)
        assertFailure(legacy.transaction(GateOperation.ReadDoorTiming))
        assertFailure(legacy.transaction(GateOperation.ReadStandbyPolicy))
        assertFailure(legacy.transaction(GateOperation.Diagnostic(GateDiagnostic.ReturnCupLamp(true))))
    }

    @Test
    fun diagnosticBoundsAndMaintenanceCapabilityAreConsistent() {
        val enabled = adapter(GateMechanism.SECTOR, maintenance = true)
        val disabled = adapter(GateMechanism.SECTOR, maintenance = false)
        val diagnostics =
            listOf(
                GateDiagnostic.Door(GateDoorTestAction.OPEN),
                GateDiagnostic.EndDisplay(GateLampColor.YELLOW, true),
                GateDiagnostic.Indicator(GateLampColor.BLUE, false),
                GateDiagnostic.Buzzer(3, true),
                GateDiagnostic.ReturnCupLamp(false),
            )
        assertTrue(diagnostics.all { enabled.transaction(GateOperation.Diagnostic(it)) is GateResult.Success })
        assertTrue(
            enabled.capabilities.containsAll(
                setOf(com.qurkos.gate.sdk.GateCapability.DIAGNOSTICS, com.qurkos.gate.sdk.GateCapability.RESET),
            ),
        )
        assertTrue(com.qurkos.gate.sdk.GateCapability.DIAGNOSTICS !in disabled.capabilities)
    }

    @Test
    fun everyPassModeUsesTheDocumentedOffsetWireByte() {
        val wireModes =
            GatePassMode.entries.map { mode ->
                val closed = adapter(GateMechanism.SECTOR, maintenance = true, normalOpen = false)
                val opened = adapter(GateMechanism.SECTOR, maintenance = true, normalOpen = true)
                val normalOpen = mode !in closed.support.passModes
                val selected = if (normalOpen) opened else closed
                assertTrue(mode in selected.support.passModes)
                val transaction = assertSuccess(selected.transaction(GateOperation.SetPassMode(mode, normalOpen)))
                PuloonFrameCodec
                    .decode(transaction.encode(0))
                    .payload[1]
                    .toInt() and 0xFF
            }

        assertEquals((0x30..0x3F).toList(), wireModes)
    }

    @Test
    fun sequenceWrapsOnlyAfterFullUnsignedRange() {
        val adapter = adapter(GateMechanism.SECTOR)
        var firstSequence = -1
        var wrappedSequence = -1
        repeat(0x1_0001) { index ->
            val transaction =
                assertSuccess(
                    adapter.transaction(GateOperation.Passage(GatePassageRequest(GateDirection.ENTRY))),
                )
            val sequence = PuloonFrameCodec.decode(transaction.encode(0)).sequence
            if (index == 0) firstSequence = sequence
            if (index == 0x1_0000) wrappedSequence = sequence
        }
        assertEquals(0, firstSequence)
        assertEquals(0, wrappedSequence)
    }

    private fun adapter(
        mechanism: GateMechanism,
        maintenance: Boolean = false,
        normalOpen: Boolean = false,
        revision: GateProtocolRevision = GateProtocolRevision.V2_8,
    ): PuloonAdapter =
        PuloonAdapter(
            GateHardwareProfile(
                mechanism = mechanism,
                site = GateSite.KOLKATA_INDIA,
                modules = setOf(GateModule.UPS, GateModule.TOKEN_CONTROL_UNIT),
                normalOpen = normalOpen,
                protocolRevision = revision,
            ),
            maintenanceOperationsEnabled = maintenance,
        )

    private fun assertSuccess(result: GateResult<SerialTransaction>): SerialTransaction =
        assertIs<GateResult.Success<SerialTransaction>>(result).value

    private fun assertFailure(result: GateResult<SerialTransaction>) {
        assertIs<GateResult.Failure>(result)
    }
}

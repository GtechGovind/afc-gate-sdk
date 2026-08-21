package com.qurkos.gate.controlpanel.ui.model

import com.qurkos.gate.sdk.GateCapability
import com.qurkos.gate.sdk.GateHardwareProfile
import com.qurkos.gate.sdk.GateMechanism
import com.qurkos.gate.sdk.GateModule
import com.qurkos.gate.sdk.GateProtocolRevision
import com.qurkos.gate.sdk.GateSite
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class ControlPanelUiModelsTest {
    @Test
    fun `V2_8 India TCU sensors use return cup and token path metadata`() {
        val sensors =
            gateSensors(
                ids = setOf(21, 22, 23, 24),
                hardware =
                    GateHardwareProfile(
                        mechanism = GateMechanism.SECTOR,
                        site = GateSite.INDIA,
                        modules = setOf(GateModule.TOKEN_CONTROL_UNIT),
                        protocolRevision = GateProtocolRevision.V2_8,
                    ),
            )

        kotlin.test.assertEquals(listOf("RC-A", "RC-B", "TCU-A", "TCU-B"), sensors.map { it.code })
        kotlin.test.assertTrue(sensors.all { it.group == SensorGroup.SECURITY })
    }

    @Test
    fun `Kolkata TCU sensors use the same India profile metadata`() {
        val sensors =
            gateSensors(
                ids = setOf(21, 22, 23, 24),
                hardware =
                    GateHardwareProfile(
                        mechanism = GateMechanism.SECTOR,
                        site = GateSite.KOLKATA_INDIA,
                        modules = setOf(GateModule.TOKEN_CONTROL_UNIT),
                        protocolRevision = GateProtocolRevision.V2_8,
                    ),
            )

        kotlin.test.assertEquals(listOf("RC-A", "RC-B", "TCU-A", "TCU-B"), sensors.map { it.code })
    }

    @Test
    fun `SwingDoor and China child inputs retain profile-specific metadata`() {
        val swing = gateSensors(setOf(23, 24), GateHardwareProfile(mechanism = GateMechanism.SWING))
        val child =
            gateSensors(
                setOf(10, 20, 21, 22),
                GateHardwareProfile(site = GateSite.CHINA, modules = setOf(GateModule.CHILD_SENSORS)),
            )

        kotlin.test.assertEquals(listOf("SW-A", "SW-B"), swing.map { it.code })
        kotlin.test.assertTrue(swing.all { it.group == SensorGroup.MECHANISM })
        kotlin.test.assertEquals(listOf("CH-10", "CH-20", "CH-21", "CH-22"), child.map { it.code })
        kotlin.test.assertTrue(child.all { it.group == SensorGroup.SAFETY })
    }

    @Test
    fun `generic diagnostics expose ordinary outputs but not return cup outputs`() {
        val identifiers = diagnosticsFor(setOf(GateCapability.DIAGNOSTICS)).mapTo(mutableSetOf()) { it.id }

        assertContains(identifiers, "door-close")
        assertContains(identifiers, "indicator-green-on")
        assertFalse("return-cup-on" in identifiers)
        assertFalse("return-cup-off" in identifiers)
    }

    @Test
    fun `return cup capability exposes only the dedicated return cup outputs`() {
        val identifiers = diagnosticsFor(setOf(GateCapability.RETURN_CUP_DIAGNOSTIC)).mapTo(mutableSetOf()) { it.id }

        assertContains(identifiers, "return-cup-on")
        assertContains(identifiers, "return-cup-off")
        assertFalse("door-close" in identifiers)
        assertFalse("indicator-green-on" in identifiers)
    }
}

package com.qurkos.gate.controlpanel.ui.screens

import com.qurkos.gate.controlpanel.ui.model.SensorGroup
import com.qurkos.gate.controlpanel.ui.model.defaultGateSensors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SensorPairingTest {
    @Test
    fun allDocumentedSectorSensorsRemainIndividuallyAddressable() {
        val pairs = pairSensors(defaultGateSensors())

        assertEquals(18, pairs.size)
        pairs.forEach { pair ->
            assertEquals(1, pair.sensors.size)
            assertTrue(pair.key.startsWith("S"))
        }
    }

    @Test
    fun filteringByGroupKeepsOnlyPopulatedPairs() {
        val safetyPairs = pairSensors(defaultGateSensors().filter { it.group == SensorGroup.SAFETY })

        assertEquals(listOf("S07", "S08", "S09", "S11", "S12", "S13"), safetyPairs.map { it.key })
        assertTrue(safetyPairs.all { it.group == SensorGroup.SAFETY })
    }
}

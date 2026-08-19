package com.qurkos.gate.controlpanel.ui.screens

import com.qurkos.gate.controlpanel.ui.model.SensorGroup
import com.qurkos.gate.controlpanel.ui.model.defaultGateSensors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SensorPairingTest {
    @Test
    fun allSensorsProduceEightOrderedLeftRightPairs() {
        val pairs = pairSensors(defaultGateSensors())

        assertEquals(8, pairs.size)
        pairs.forEach { pair ->
            assertEquals(2, pair.sensors.size)
            assertTrue(
                pair.sensors
                    .first()
                    .code
                    .endsWith("-L"),
            )
            assertTrue(
                pair.sensors
                    .last()
                    .code
                    .endsWith("-R"),
            )
        }
    }

    @Test
    fun filteringByGroupKeepsOnlyPopulatedPairs() {
        val safetyPairs = pairSensors(defaultGateSensors().filter { it.group == SensorGroup.SAFETY })

        assertEquals(listOf("ES", "XS", "CH"), safetyPairs.map { it.key })
        assertTrue(safetyPairs.all { it.group == SensorGroup.SAFETY })
    }
}

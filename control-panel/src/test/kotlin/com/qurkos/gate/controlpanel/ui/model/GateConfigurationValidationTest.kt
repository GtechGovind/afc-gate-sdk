package com.qurkos.gate.controlpanel.ui.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GateConfigurationValidationTest {
    @Test
    fun productionDefaultsFitPuloonProtocolRanges() {
        assertTrue(GateConfigurationUi().hasValidInputs())
    }

    @Test
    fun rejectsUnsupportedDoorTimingStep() {
        assertFalse(GateConfigurationUi(openDurationMs = "950").hasValidInputs())
    }

    @Test
    fun rejectsOutOfRangeControllerLevels() {
        assertFalse(GateConfigurationUi(hurryUpLevel = "4").hasValidInputs())
        assertFalse(GateConfigurationUi(tailingSensitivity = "-1").hasValidInputs())
    }

    @Test
    fun rejectsBlankAndNonNumericValues() {
        assertFalse(GateConfigurationUi(serialPort = "").hasValidInputs())
        assertFalse(GateConfigurationUi(responseTimeoutMs = "one second").hasValidInputs())
    }

    @Test
    fun acceptsOnlyImplementedProtocolRevisions() {
        assertTrue(GateConfigurationUi(protocolRevision = "V2_5").hasValidInputs())
        assertTrue(GateConfigurationUi(protocolRevision = "V2_8").hasValidInputs())
        assertFalse(GateConfigurationUi(protocolRevision = "V3_0").hasValidInputs())
    }
}

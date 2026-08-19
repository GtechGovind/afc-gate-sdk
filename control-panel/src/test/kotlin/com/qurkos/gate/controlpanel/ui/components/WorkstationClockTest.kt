package com.qurkos.gate.controlpanel.ui.components

import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkstationClockTest {
    @Test
    fun formatsDateAndZeroPadsClockFields() {
        val timestamp = LocalDateTime(2026, 8, 20, 2, 3, 4)

        assertEquals("20 August 2026\n02:03:04", timestamp.displayText())
    }
}

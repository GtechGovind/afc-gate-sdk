package com.qurkos.gate.controlpanel.app

import com.qurkos.gate.controlpanel.ui.model.EventCategory
import com.qurkos.gate.controlpanel.ui.model.EventSeverity
import com.qurkos.gate.controlpanel.ui.model.GateEventUi
import kotlin.test.Test
import kotlin.test.assertContains

class EventLogCsvExporterTest {
    @Test
    fun escapesCommasQuotesAndLineBreaks() {
        val csv =
            listOf(
                GateEventUi(
                    id = "event-1",
                    timestamp = "T+001",
                    title = "Passage, rejected",
                    detail = "Reader said \"invalid\"\nTry again",
                    severity = EventSeverity.WARNING,
                    category = EventCategory.PASSAGE,
                ),
            ).toCsv()

        assertContains(csv, "\"Passage, rejected\"")
        assertContains(csv, "\"Reader said \"\"invalid\"\"\nTry again\"")
    }
}

package com.qurkos.gate.controlpanel.app

import com.qurkos.gate.controlpanel.ui.model.GateEventUi
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** Opens the native desktop save dialog and writes the selected controller history as UTF-8 CSV. */
internal fun exportEventLog(events: List<GateEventUi>): String? {
    val dialog = FileDialog(null as Frame?, "Export AFC gate events", FileDialog.SAVE)
    val selection =
        try {
            dialog.file = "afc-gate-events.csv"
            dialog.isVisible = true
            dialog.directory?.let { directory -> dialog.file?.let { file -> directory to file } }
        } finally {
            dialog.dispose()
        }
    val (directory, selectedFile) = selection ?: return null
    val target = File(directory, selectedFile.ensureCsvExtension())
    target.writeText(events.toCsv())
    return target.absolutePath
}

/** Produces a stable RFC 4180-compatible event export without locale-sensitive formatting. */
internal fun List<GateEventUi>.toCsv(): String =
    buildString {
        appendLine("id,timestamp,severity,category,title,detail")
        this@toCsv.forEach { event ->
            appendLine(
                listOf(
                    event.id,
                    event.timestamp,
                    event.severity.name,
                    event.category.name,
                    event.title,
                    event.detail,
                ).joinToString(",", transform = String::toCsvCell),
            )
        }
    }

private fun String.ensureCsvExtension(): String = if (endsWith(".csv", ignoreCase = true)) this else "$this.csv"

private fun String.toCsvCell(): String = "\"${replace("\"", "\"\"")}\""

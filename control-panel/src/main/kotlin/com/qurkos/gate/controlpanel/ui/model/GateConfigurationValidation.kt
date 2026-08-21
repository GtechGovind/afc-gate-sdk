package com.qurkos.gate.controlpanel.ui.model

/** Inclusive numeric input rule shared by the form and controller preflight validation. */
internal data class NumericInputRule(
    val range: LongRange,
    val step: Long = 1,
    val unit: String? = null,
) {
    init {
        require(!range.isEmpty()) { "Numeric input range cannot be empty" }
        require(step > 0) { "Numeric input step must be positive" }
    }

    val guidance: String
        get() =
            buildString {
                append("${range.first}–${range.last}")
                unit?.let { append(" $it") }
                if (step > 1) append(" · step $step")
            }

    fun accepts(value: String): Boolean {
        val parsed = value.toLongOrNull() ?: return false
        return parsed in range && (parsed - range.first) % step == 0L
    }
}

/** Puloon GCU limits used to reject invalid form values before any hardware write. */
internal object PuloonInputRules {
    val baudRate = NumericInputRule(1L..1_000_000L)
    val responseTimeout = NumericInputRule(1L..60_000L, unit = "ms")
    val pollInterval = NumericInputRule(101L..60_000L, unit = "ms")
    val doorTiming = NumericInputRule(0L..1_000L, step = 100, unit = "ms")
    val upsShutdownDelay = NumericInputRule(0L..2_550L, step = 10, unit = "s")
    val standbyTimeout = NumericInputRule(0L..255L, unit = "s")
    val byteSetting = NumericInputRule(0L..255L)
    val noEntryTimeout = NumericInputRule(0L..999L, unit = "s")
    val buzzerTimeout = NumericInputRule(0L..254L, unit = "raw units")
    val safetyRegionTimeout = NumericInputRule(0L..255L, unit = "s; 255 disables")
}

/** Returns whether every editable value can be represented by the current Puloon adapter. */
@Suppress("CyclomaticComplexMethod") // One centralized preflight intentionally covers every independently editable field.
internal fun GateConfigurationUi.hasValidInputs(): Boolean =
    listOf(
        serialPort.isNotBlank(),
        protocolRevision == "V2_5" || protocolRevision == "V2_8",
        mechanism == "SECTOR" || mechanism == "SWING",
        controllerVariant == "STANDARD" || controllerVariant == "BLDC",
        site in setOf("GENERIC", "INDIA", "KOLKATA_INDIA", "CHINA"),
        passageMode.isNotBlank(),
        standbyPassMode.isNotBlank(),
        PuloonInputRules.baudRate.accepts(baudRate),
        PuloonInputRules.responseTimeout.accepts(responseTimeoutMs),
        PuloonInputRules.pollInterval.accepts(pollIntervalMs),
        PuloonInputRules.doorTiming.accepts(openDurationMs),
        PuloonInputRules.doorTiming.accepts(closeDelayMs),
        safetyRegion.toIntOrNull() in if (mechanism == "SWING") 1..3 else 1..6,
        PuloonInputRules.upsShutdownDelay.accepts(upsShutdownDelaySeconds),
        PuloonInputRules.standbyTimeout.accepts(standbyTimeoutSeconds),
        PuloonInputRules.noEntryTimeout.accepts(noEntryTimeoutSeconds),
        PuloonInputRules.buzzerTimeout.accepts(buzzerTimeoutUnits),
        PuloonInputRules.safetyRegionTimeout.accepts(safetyRegionTimeoutSeconds),
        tailingSensitivity.toIntOrNull() in 0..1,
        hurryUpLevel.toIntOrNull() in 0..3,
        childDetectionLevel.toIntOrNull() in 0..2,
        !normalOpenMode || mechanism != "SWING",
        childDetectionLevel == "0" || (site == "CHINA" && childSensorsInstalled),
        !upsInstalled || site == "INDIA" || site == "KOLKATA_INDIA",
        !tokenControlUnitInstalled || site == "INDIA" || site == "KOLKATA_INDIA",
        controllerVariant != "BLDC" || mechanism == "SECTOR",
        !tokenControlUnitInstalled || protocolRevision == "V2_8",
        !tokenControlUnitInstalled || mechanism == "SECTOR",
    ).all { it }

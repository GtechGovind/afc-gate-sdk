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
}

/** Returns whether every editable value can be represented by the current Puloon adapter. */
internal fun GateConfigurationUi.hasValidInputs(): Boolean =
    listOf(
        serialPort.isNotBlank(),
        passageMode.isNotBlank(),
        standbyPassMode.isNotBlank(),
        PuloonInputRules.baudRate.accepts(baudRate),
        PuloonInputRules.responseTimeout.accepts(responseTimeoutMs),
        PuloonInputRules.pollInterval.accepts(pollIntervalMs),
        PuloonInputRules.doorTiming.accepts(openDurationMs),
        PuloonInputRules.doorTiming.accepts(closeDelayMs),
        safetyRegion.toIntOrNull() in 1..3,
        PuloonInputRules.upsShutdownDelay.accepts(upsShutdownDelaySeconds),
        PuloonInputRules.standbyTimeout.accepts(standbyTimeoutSeconds),
        PuloonInputRules.byteSetting.accepts(sensorSensitivity),
        PuloonInputRules.byteSetting.accepts(passageTimeoutSeconds),
        PuloonInputRules.byteSetting.accepts(childHeight),
        tailingSensitivity.toIntOrNull() in 0..2,
        hurryUpLevel.toIntOrNull() in 0..2,
    ).all { it }

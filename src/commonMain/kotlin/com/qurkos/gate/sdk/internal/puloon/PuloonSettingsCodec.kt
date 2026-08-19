package com.qurkos.gate.sdk.internal.puloon

import com.qurkos.gate.sdk.GateSetting
import kotlin.time.Duration.Companion.seconds

/** Strict codec for the complete fixed-width Puloon `P` settings block. */
internal object PuloonSettingsCodec {
    /** Validates all setting fields and returns exactly one typed value per supported setting. */
    fun decode(bytes: ByteArray): Set<GateSetting> {
        require(bytes.size >= SETTINGS_LENGTH) { "Puloon settings payload is too short" }
        val childHeight =
            if (bytes[8] == ascii('@') && bytes[9] == ascii('@')) {
                null
            } else {
                decodeAsciiHex(bytes, 8)
            }
        return setOf(
            GateSetting.SensorSensitivity(decodeAsciiHex(bytes, 0)),
            GateSetting.NormalOpenMode(decodeBoolean(bytes[2], trueValue = '0', falseValue = '1', "normal-open mode")),
            GateSetting.HurryUpLevel(decodeDecimalDigit(bytes[3], "hurry-up level")),
            GateSetting.TagTimeoutFromLastTag(decodeBoolean(bytes[4], trueValue = '1', falseValue = '0', "tag timeout")),
            GateSetting.TailingSensitivity(decodeDecimalDigit(bytes[5], "tailing sensitivity")),
            GateSetting.PassageTimeout(decodeAsciiHex(bytes, 6).seconds),
            GateSetting.ChildHeight(childHeight),
            GateSetting.ChildDetection(decodeBoolean(bytes[10], trueValue = '1', falseValue = '0', "child detection")),
        )
    }

    /** Validates completeness, uniqueness, and ranges before encoding the full block. */
    fun encode(settings: Set<GateSetting>): ByteArray {
        val values = PuloonSettingValues.from(settings)
        return buildList {
            addAll(encodeAsciiHex(values.sensorSensitivity).toList())
            add(ascii(if (values.normalOpen) '0' else '1'))
            add((ascii('0') + values.hurryUpLevel).toByte())
            add(ascii(if (values.tagTimeoutFromLast) '1' else '0'))
            add((ascii('0') + values.tailingSensitivity).toByte())
            addAll(encodeAsciiHex(values.passageTimeoutSeconds).toList())
            addAll(values.childHeight?.let(::encodeAsciiHex)?.toList() ?: listOf(ascii('@'), ascii('@')))
            add(ascii(if (values.childDetection) '1' else '0'))
        }.toByteArray()
    }

    /** Encodes an already range-checked unsigned byte as hexadecimal ASCII. */
    private fun encodeAsciiHex(value: Int): ByteArray =
        value
            .toString(HEX_BASE)
            .uppercase()
            .padStart(2, '0')
            .encodeToByteArray()

    /** Strictly decodes two hexadecimal ASCII characters at [offset]. */
    private fun decodeAsciiHex(
        bytes: ByteArray,
        offset: Int,
    ): Int = bytes.copyOfRange(offset, offset + 2).decodeToString().toInt(HEX_BASE)

    /** Decodes a field with explicitly documented true and false characters. */
    private fun decodeBoolean(
        value: Byte,
        trueValue: Char,
        falseValue: Char,
        name: String,
    ): Boolean =
        when (value) {
            ascii(trueValue) -> true
            ascii(falseValue) -> false
            else -> throw IllegalArgumentException("Invalid Puloon $name value")
        }

    /** Decodes one strict decimal digit for a bounded controller level. */
    private fun decodeDecimalDigit(
        value: Byte,
        name: String,
    ): Int = value.toInt().toChar().digitToIntOrNull() ?: throw IllegalArgumentException("Invalid Puloon $name value")

    private const val SETTINGS_LENGTH = 11
    private const val HEX_BASE = 16
}

/** Fully populated and range-checked intermediate settings representation. */
private data class PuloonSettingValues(
    val normalOpen: Boolean,
    val sensorSensitivity: Int,
    val hurryUpLevel: Int,
    val tagTimeoutFromLast: Boolean,
    val tailingSensitivity: Int,
    val passageTimeoutSeconds: Int,
    val childHeight: Int?,
    val childDetection: Boolean,
) {
    companion object {
        /** Collects [settings] while enforcing one value per subtype. */
        fun from(settings: Set<GateSetting>): PuloonSettingValues {
            val collector = PuloonSettingCollector()
            settings.forEach(collector::accept)
            return collector.values()
        }
    }
}

/** Mutable, invocation-local collector used to reject missing or duplicate setting types deterministically. */
private class PuloonSettingCollector {
    private var normalOpen: Boolean? = null
    private var sensorSensitivity: Int? = null
    private var hurryUpLevel: Int? = null
    private var tagTimeoutFromLast: Boolean? = null
    private var tailingSensitivity: Int? = null
    private var passageTimeoutSeconds: Int? = null
    private var childHeightSeen = false
    private var childHeight: Int? = null
    private var childDetection: Boolean? = null

    /** Records one [setting] after subtype uniqueness and basic duration validation. */
    fun accept(setting: GateSetting) {
        when (setting) {
            is GateSetting.NormalOpenMode -> {
                require(normalOpen == null) { "NormalOpenMode setting must be unique" }
                normalOpen = setting.enabled
            }
            is GateSetting.SensorSensitivity -> {
                require(sensorSensitivity == null) { "SensorSensitivity setting must be unique" }
                sensorSensitivity = setting.value
            }
            is GateSetting.HurryUpLevel -> {
                require(hurryUpLevel == null) { "HurryUpLevel setting must be unique" }
                hurryUpLevel = setting.level
            }
            is GateSetting.TagTimeoutFromLastTag -> {
                require(tagTimeoutFromLast == null) { "TagTimeoutFromLastTag setting must be unique" }
                tagTimeoutFromLast = setting.enabled
            }
            is GateSetting.TailingSensitivity -> {
                require(tailingSensitivity == null) { "TailingSensitivity setting must be unique" }
                tailingSensitivity = setting.level
            }
            is GateSetting.PassageTimeout -> {
                require(passageTimeoutSeconds == null) { "PassageTimeout setting must be unique" }
                require(setting.value.isFinite()) { "PassageTimeout must be finite" }
                passageTimeoutSeconds = setting.value.inWholeSeconds.toInt()
            }
            is GateSetting.ChildHeight -> {
                require(!childHeightSeen) { "ChildHeight setting must be unique" }
                childHeightSeen = true
                childHeight = setting.value
            }
            is GateSetting.ChildDetection -> {
                require(childDetection == null) { "ChildDetection setting must be unique" }
                childDetection = setting.enabled
            }
        }
    }

    /** Produces an immutable complete representation after all protocol range checks. */
    fun values(): PuloonSettingValues {
        require(childHeightSeen) { "ChildHeight setting is required" }
        return PuloonSettingValues(
            normalOpen = requireNotNull(normalOpen) { "NormalOpenMode setting is required" },
            sensorSensitivity = requireRange(sensorSensitivity, 0..0xFF, "SensorSensitivity"),
            hurryUpLevel = requireRange(hurryUpLevel, 0..2, "HurryUpLevel"),
            tagTimeoutFromLast = requireNotNull(tagTimeoutFromLast) { "TagTimeoutFromLastTag setting is required" },
            tailingSensitivity = requireRange(tailingSensitivity, 0..2, "TailingSensitivity"),
            passageTimeoutSeconds = requireRange(passageTimeoutSeconds, 0..0xFF, "PassageTimeout"),
            childHeight = childHeight?.also { require(it in 0..0xFF) },
            childDetection = requireNotNull(childDetection) { "ChildDetection setting is required" },
        )
    }

    /** Returns [value] when present and inside [range], otherwise throws a descriptive validation failure. */
    private fun requireRange(
        value: Int?,
        range: IntRange,
        name: String,
    ): Int {
        require(value != null && value in range) { "$name must be in $range" }
        return value
    }
}

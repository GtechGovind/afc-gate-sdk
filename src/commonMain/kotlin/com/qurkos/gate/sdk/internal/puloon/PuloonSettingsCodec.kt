package com.qurkos.gate.sdk.internal.puloon

import com.qurkos.gate.sdk.GateSetting
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Strict codec for the complete 12-byte Puloon `P` parameter block. */
internal object PuloonSettingsCodec {
    /** Decodes fields in the exact order documented by GCU specification section 4.3.5. */
    fun decode(bytes: ByteArray): Set<GateSetting> {
        require(bytes.size == SETTINGS_LENGTH) { "Puloon settings payload must contain exactly $SETTINGS_LENGTH bytes" }
        return setOf(
            GateSetting.NoEntryTimeout(decodeDecimal(bytes, 0, NO_ENTRY_TIMEOUT_LENGTH).seconds),
            GateSetting.NormalOpenMode(decodeBoolean(bytes[3], '0', '1', "gate mode")),
            GateSetting.HurryUpLevel(decodeDigit(bytes[4], 0..3, "hurry-up level")),
            GateSetting.TagTimeoutFromLastTag(decodeBoolean(bytes[5], '1', '0', "tag timeout")),
            GateSetting.TailingSensitivity(decodeDigit(bytes[6], 0..1, "tailing performance")),
            GateSetting.BuzzerTimeoutUnits(
                PuloonOffsetHexCodec.decode(bytes, 7, "buzzer timeout").also {
                    require(it <= MAX_BUZZER_UNITS) { "Invalid buzzer timeout" }
                },
            ),
            GateSetting.SafetyRegionTimeout(
                PuloonOffsetHexCodec.decode(bytes, 9, "safety-region timeout").let {
                    if (it == DISABLED_TIMEOUT) null else it.seconds
                },
            ),
            GateSetting.ChildDetection(decodeDigit(bytes[11], 0..2, "child detection")),
        )
    }

    /** Validates a complete typed set and encodes exactly 12 documented parameter bytes. */
    fun encode(settings: Set<GateSetting>): ByteArray {
        val values = PuloonSettingCollector().apply { settings.forEach(::accept) }.values()
        return buildList {
            addAll(
                values.noEntryTimeoutSeconds
                    .toString()
                    .padStart(NO_ENTRY_TIMEOUT_LENGTH, '0')
                    .encodeToByteArray()
                    .toList(),
            )
            add(ascii(if (values.normalOpen) '0' else '1'))
            add((ascii('0') + values.hurryUpLevel).toByte())
            add(ascii(if (values.tagTimeoutFromLast) '1' else '0'))
            add((ascii('0') + values.tailingSensitivity).toByte())
            addAll(PuloonOffsetHexCodec.encode(values.buzzerTimeoutUnits).toList())
            addAll(PuloonOffsetHexCodec.encode(values.safetyRegionTimeoutSeconds ?: DISABLED_TIMEOUT).toList())
            add((ascii('0') + values.childDetectionLevel).toByte())
        }.toByteArray()
    }

    private fun decodeDecimal(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Int =
        bytes.copyOfRange(offset, offset + length).decodeToString().toIntOrNull()
            ?: throw IllegalArgumentException("Invalid Puloon decimal setting")

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

    private fun decodeDigit(
        value: Byte,
        range: IntRange,
        name: String,
    ): Int {
        val decoded =
            value.toInt().toChar().digitToIntOrNull()
                ?: throw IllegalArgumentException("Invalid Puloon $name value")
        require(decoded in range) { "Puloon $name must be in $range" }
        return decoded
    }

    private const val SETTINGS_LENGTH = 12
    private const val NO_ENTRY_TIMEOUT_LENGTH = 3
    private const val MAX_BUZZER_UNITS = 0xFE
    private const val DISABLED_TIMEOUT = 0xFF
}

private data class PuloonSettingValues(
    val normalOpen: Boolean,
    val noEntryTimeoutSeconds: Int,
    val hurryUpLevel: Int,
    val tagTimeoutFromLast: Boolean,
    val tailingSensitivity: Int,
    val buzzerTimeoutUnits: Int,
    val safetyRegionTimeoutSeconds: Int?,
    val childDetectionLevel: Int,
)

/** Invocation-local collector that enforces one value for each documented setting. */
private class PuloonSettingCollector {
    private var normalOpen: Boolean? = null
    private var noEntryTimeout: Duration? = null
    private var hurryUpLevel: Int? = null
    private var tagTimeoutFromLast: Boolean? = null
    private var tailingSensitivity: Int? = null
    private var buzzerTimeoutUnits: Int? = null
    private var safetyRegionSeen = false
    private var safetyRegionTimeout: Duration? = null
    private var childDetectionLevel: Int? = null

    fun accept(setting: GateSetting) {
        when (setting) {
            is GateSetting.NormalOpenMode -> {
                require(normalOpen == null) { "NormalOpenMode setting must be unique" }
                normalOpen = setting.enabled
            }
            is GateSetting.NoEntryTimeout -> {
                require(noEntryTimeout == null) { "NoEntryTimeout setting must be unique" }
                noEntryTimeout = setting.value
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
            is GateSetting.BuzzerTimeoutUnits -> {
                require(buzzerTimeoutUnits == null) { "BuzzerTimeoutUnits setting must be unique" }
                buzzerTimeoutUnits = setting.value
            }
            is GateSetting.SafetyRegionTimeout -> {
                require(!safetyRegionSeen) { "SafetyRegionTimeout setting must be unique" }
                safetyRegionSeen = true
                safetyRegionTimeout = setting.value
            }
            is GateSetting.ChildDetection -> {
                require(childDetectionLevel == null) { "ChildDetection setting must be unique" }
                childDetectionLevel = setting.level
            }
        }
    }

    fun values(): PuloonSettingValues {
        val noEntry = requireNotNull(noEntryTimeout) { "NoEntryTimeout setting is required" }
        require(noEntry.isFinite() && noEntry.inWholeSeconds in 0..MAX_NO_ENTRY_SECONDS) {
            "NoEntryTimeout must be 0..$MAX_NO_ENTRY_SECONDS whole seconds"
        }
        require(noEntry.inWholeMilliseconds % MILLIS_PER_SECOND == 0L) { "NoEntryTimeout must use whole seconds" }
        val safety = safetyRegionTimeout
        require(safetyRegionSeen) { "SafetyRegionTimeout setting is required" }
        require(safety == null || safety.isFinite()) { "SafetyRegionTimeout must be finite or disabled" }
        require(safety == null || safety.inWholeSeconds in 0..MAX_SAFETY_SECONDS) {
            "SafetyRegionTimeout must be 0..$MAX_SAFETY_SECONDS whole seconds or disabled"
        }
        require(safety == null || safety.inWholeMilliseconds % MILLIS_PER_SECOND == 0L) {
            "SafetyRegionTimeout must use whole seconds"
        }
        return PuloonSettingValues(
            normalOpen = requireNotNull(normalOpen) { "NormalOpenMode setting is required" },
            noEntryTimeoutSeconds = noEntry.inWholeSeconds.toInt(),
            hurryUpLevel = requireRange(hurryUpLevel, 0..3, "HurryUpLevel"),
            tagTimeoutFromLast = requireNotNull(tagTimeoutFromLast) { "TagTimeoutFromLastTag setting is required" },
            tailingSensitivity = requireRange(tailingSensitivity, 0..1, "TailingSensitivity"),
            buzzerTimeoutUnits = requireRange(buzzerTimeoutUnits, 0..0xFE, "BuzzerTimeoutUnits"),
            safetyRegionTimeoutSeconds = safety?.inWholeSeconds?.toInt(),
            childDetectionLevel = requireRange(childDetectionLevel, 0..2, "ChildDetection"),
        )
    }

    private fun requireRange(
        value: Int?,
        range: IntRange,
        name: String,
    ): Int {
        require(value != null && value in range) { "$name must be in $range" }
        return value
    }

    private companion object {
        const val MAX_NO_ENTRY_SECONDS = 999
        const val MAX_SAFETY_SECONDS = 254
        const val MILLIS_PER_SECOND = 1_000L
    }
}

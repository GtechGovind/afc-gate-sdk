package com.qurkos.gate.sdk.puloon

import com.qurkos.gate.sdk.GateClock
import com.qurkos.gate.sdk.GateDeviceConfig
import com.qurkos.gate.sdk.GateDiagnostic
import com.qurkos.gate.sdk.GateDoorTestAction
import com.qurkos.gate.sdk.GateDoorTiming
import com.qurkos.gate.sdk.GateHardwareProfile
import com.qurkos.gate.sdk.GateMechanism
import com.qurkos.gate.sdk.GateModule
import com.qurkos.gate.sdk.GatePassMode
import com.qurkos.gate.sdk.GateResult
import com.qurkos.gate.sdk.GateRuntimeOptions
import com.qurkos.gate.sdk.GateSafetyRegion
import com.qurkos.gate.sdk.GateSetting
import com.qurkos.gate.sdk.GateSite
import com.qurkos.gate.sdk.GateStandbyPolicy
import com.qurkos.gate.sdk.GateVendor
import com.qurkos.gate.sdk.ReconnectPolicy
import com.qurkos.gate.sdk.SerialConnectionConfig
import com.qurkos.gate.sdk.SerialParameters
import com.qurkos.gate.sdk.SerialPortName
import com.qurkos.gate.sdk.TestSerialTransport
import com.qurkos.gate.sdk.internal.SerialGateController
import com.qurkos.gate.sdk.internal.puloon.PuloonAdapter
import com.qurkos.gate.sdk.internal.puloon.PuloonFrameCodec
import com.qurkos.gate.sdk.internal.puloon.PuloonSettingsCodec
import com.qurkos.gate.sdk.internal.puloon.ascii
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PuloonAdapterTest {
    @Test
    fun unifiedApiCoversEveryDocumentedCommandGroup() =
        runBlocking {
            val settings = completeSettings()
            val transport =
                TestSerialTransport { request, fake ->
                    val data = responseData(request.command.toInt().toChar(), request.payload, settings)
                    fake.respond(request, byteArrayOf(request.command, ascii('0'), ascii('0')) + data)
                }
            val hardware =
                GateHardwareProfile(
                    mechanism = GateMechanism.SECTOR,
                    site = GateSite.KOLKATA_INDIA,
                    modules = setOf(GateModule.UPS, GateModule.TOKEN_CONTROL_UNIT),
                )
            val gate = createGate(transport, hardware, maintenance = true)
            gate.connect()

            val results =
                listOf(
                    gate.firmware(),
                    gate.allowEntry(),
                    gate.setEmergency(true),
                    gate.initialize(),
                    gate.readSettings(),
                    gate.applySettings(settings),
                    gate.reset(),
                    gate.refreshStatus(),
                    gate.runDiagnostic(GateDiagnostic.Door(GateDoorTestAction.OPEN)),
                    gate.clearPassageCounters(),
                    gate.readSensors(),
                    gate.setPassMode(GatePassMode.CONTROLLED_BOTH),
                    gate.setSafetyRegion(GateSafetyRegion(1)),
                    gate.readClock(),
                    gate.setClock(GateClock(LocalDateTime(2026, 8, 20, 12, 34, 56))),
                    gate.setUpsShutdownDelaySeconds(100),
                    gate.readStandbyPolicy(),
                    gate.setStandbyPolicy(GateStandbyPolicy(20.seconds, GatePassMode.OUT_OF_SERVICE)),
                    gate.readDoorTiming(),
                    gate.setDoorTiming(GateDoorTiming(500.milliseconds, 1_000.milliseconds)),
                )

            assertTrue(results.all { it is GateResult.Success<*> })
            val commands =
                transport.writes
                    .map {
                        PuloonFrameCodec
                            .decode(it)
                            .command
                            .toInt()
                            .toChar()
                    }.toSet()
            assertEquals(setOf('V', 'A', 'E', 'I', 'P', 'R', 'S', 'T', 'C', 'H', 'D', 'G', 'X', 'Y', 'U'), commands)
            gate.disconnect()
            Unit
        }

    @Test
    fun passageWriteIsNeverRetriedAfterTimeout() =
        runBlocking {
            val transport =
                TestSerialTransport { request, fake ->
                    if (request.command == ascii('S')) {
                        fake.respond(
                            request,
                            byteArrayOf(request.command, ascii('0'), ascii('0')) +
                                baseStatus().copyOfRange(0, 23),
                        )
                    }
                }
            val gate = createGate(transport, GateHardwareProfile(site = GateSite.INDIA), maintenance = false)
            gate.connect()

            assertIs<GateResult.Failure>(gate.allowEntry())

            assertEquals(1, transport.writes.map(PuloonFrameCodec::decode).count { it.command == ascii('A') })
            gate.disconnect()
            Unit
        }

    private fun createGate(
        transport: TestSerialTransport,
        hardware: GateHardwareProfile,
        maintenance: Boolean,
    ) = SerialGateController(
        config =
            GateDeviceConfig(
                vendor = GateVendor.PULOON,
                serial = SerialConnectionConfig(SerialPortName("fake"), SerialParameters(57_600)),
                hardware = hardware,
                runtime =
                    GateRuntimeOptions(
                        responseTimeout = 30.milliseconds,
                        statusPollInterval = null,
                        reconnectPolicy = ReconnectPolicy.Disabled,
                    ),
                maintenanceOperationsEnabled = maintenance,
            ),
        adapter = PuloonAdapter(hardware, maintenance),
        transport = transport,
        dispatcher = Dispatchers.Default,
    )

    private fun responseData(
        command: Char,
        request: ByteArray,
        settings: Set<GateSetting>,
    ): ByteArray =
        when (command) {
            'V' -> "01.23".encodeToByteArray()
            'P' -> {
                if (request.getOrNull(1) == ascii('1')) {
                    byteArrayOf(ascii('1')) + PuloonSettingsCodec.encode(settings)
                } else {
                    ByteArray(0)
                }
            }
            'S' -> baseStatus()
            'H' -> "000000000000".encodeToByteArray()
            'X' -> if (request.getOrNull(1) == ascii('1')) "1260820123456".encodeToByteArray() else ByteArray(0)
            'U' -> extensionResponse(request)
            else -> ByteArray(0)
        }

    private fun extensionResponse(request: ByteArray): ByteArray {
        if (request.getOrNull(1) != ascii('1')) return ByteArray(0)
        return when (request.copyOfRange(2, 6).decodeToString()) {
            "2402" -> byteArrayOf(ascii('1'), ascii('1'), ascii('4'), ascii('3'), 0x3F)
            "1102" -> byteArrayOf(ascii('1'), ascii('0'), ascii('5'), ascii('0'), ascii(':'))
            else -> error("Unexpected extension selector")
        }
    }

    private fun baseStatus(): ByteArray =
        byteArrayOf(ascii('0')) + "0000".encodeToByteArray() +
            byteArrayOf(ascii('0'), ascii('0'), ascii('0'), 0x40, 0x80.toByte()) +
            "@@@00000000".encodeToByteArray() + byteArrayOf(ascii('0'), ascii('0')) +
            byteArrayOf(0x00, 0x00) + "00000000".encodeToByteArray()

    private fun completeSettings(): Set<GateSetting> =
        setOf(
            GateSetting.NoEntryTimeout(100.seconds),
            GateSetting.NormalOpenMode(false),
            GateSetting.HurryUpLevel(2),
            GateSetting.TagTimeoutFromLastTag(true),
            GateSetting.TailingSensitivity(1),
            GateSetting.BuzzerTimeoutUnits(15),
            GateSetting.SafetyRegionTimeout(100.seconds),
            GateSetting.ChildDetection(0),
        )
}

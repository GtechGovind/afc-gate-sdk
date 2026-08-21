package com.qurkos.gate.controlpanel.app

import com.qurkos.gate.sdk.GateConnectionState
import com.qurkos.gate.sdk.GateDeviceConfig
import com.qurkos.gate.sdk.GateEmergencyState
import com.qurkos.gate.sdk.GateEvent
import com.qurkos.gate.sdk.GateHardwareProfile
import com.qurkos.gate.sdk.GateMechanism
import com.qurkos.gate.sdk.GatePassMode
import com.qurkos.gate.sdk.GateRuntimeOptions
import com.qurkos.gate.sdk.GateSensorStatus
import com.qurkos.gate.sdk.GateSite
import com.qurkos.gate.sdk.GateStatus
import com.qurkos.gate.sdk.GateVendor
import com.qurkos.gate.sdk.ReconnectPolicy
import com.qurkos.gate.sdk.SerialConnectionConfig
import com.qurkos.gate.sdk.SerialParameters
import com.qurkos.gate.sdk.SerialPortName
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

class ApplicationLogTest {
    @Test
    fun resolvesPlatformStandardLogDirectories() {
        val home = Path.of("users", "operator")

        assertEquals(
            Path.of("local-data", "Qurkos", "AFC Gate Control Panel", "logs"),
            resolveLogDirectory("Windows 11", home, mapOf("LOCALAPPDATA" to "local-data"), null),
        )
        assertEquals(
            home.resolve("Library/Logs/AFC Gate Control Panel"),
            resolveLogDirectory("Mac OS X", home, emptyMap(), null),
        )
        assertEquals(
            Path.of("state-data", "afc-gate-control-panel", "logs"),
            resolveLogDirectory("Linux", home, mapOf("XDG_STATE_HOME" to "state-data"), null),
        )
        assertEquals(
            Path.of("custom-logs"),
            resolveLogDirectory("Linux", home, emptyMap(), "custom-logs"),
        )
    }

    @Test
    fun writesUtf8LogsAndRotatesWithinTheRetentionLimit() {
        val directory = createTempDirectory("afc-gate-logs")
        try {
            RotatingApplicationLogger(
                directory,
                maxFileBytes = 512,
                retainedFiles = 3,
                namespace = "com.qurkos.gate.controlpanel.test.${System.nanoTime()}",
            ).use { logger ->
                repeat(40) { index ->
                    logger.log(Level.INFO, "persistent-message-$index ${"x".repeat(80)}")
                }
            }

            val files = Files.list(directory).use { paths -> paths.filter(Files::isRegularFile).toList() }
            assertTrue(files.isNotEmpty())
            assertTrue(files.size <= 3)
            assertTrue(files.any { path -> path.readText().contains("persistent-message-39") })
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun writesRuntimeConfigurationLifecycleProtocolAndChangedStatusDiagnostics() {
        val directory = createTempDirectory("afc-gate-diagnostics")
        try {
            RotatingApplicationLogger(
                directory,
                maxFileBytes = 64 * 1024,
                retainedFiles = 2,
                namespace = "com.qurkos.gate.controlpanel.diagnostic.${System.nanoTime()}",
            ).use { applicationLogger ->
                val logger = applicationLogger.logger("diagnostic")
                logger.info("runtime ${ApplicationLogging.runtimeSummary()}")
                logger.info(sampleConfiguration().diagnosticLogMessage())
                logger.sdkEvent(GateEvent.ConnectionChanged(GateConnectionState.CONNECTING))
                logger.sdkEvent(GateEvent.ReconnectAttempt(2))
                logger.sdkEvent(GateEvent.ProtocolWarning("CRC mismatch; frameHex=0A 00"))
                logger.sdkEvent(GateEvent.StatusChanged(sampleStatus()))
            }

            val log = Files.list(directory).use { paths -> paths.filter(Files::isRegularFile).toList() }.joinToString { it.readText() }
            assertTrue(log.contains("javaVersion="))
            assertTrue(log.contains("gateConfiguration vendor=PULOON"))
            assertTrue(log.contains("port=\"COM7\""))
            assertTrue(log.contains("serialLifecycle state=CONNECTING"))
            assertTrue(log.contains("serialReconnect attempt=2"))
            assertTrue(log.contains("protocolDiagnostic detail=\"CRC mismatch; frameHex=0A 00\""))
            assertTrue(log.contains("statusChanged passMode=CONTROLLED_BOTH"))
            assertTrue(log.contains("switchesOn=[1]"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun sampleConfiguration(): GateDeviceConfig =
        GateDeviceConfig(
            vendor = GateVendor.PULOON,
            serial = SerialConnectionConfig(SerialPortName("COM7"), SerialParameters(57_600)),
            hardware = GateHardwareProfile(GateMechanism.SECTOR, GateSite.INDIA),
            runtime =
                GateRuntimeOptions(
                    responseTimeout = 900.milliseconds,
                    statusPollInterval = 500.milliseconds,
                    reconnectPolicy = ReconnectPolicy.Disabled,
                ),
        )

    private fun sampleStatus(): GateStatus =
        GateStatus(
            passMode = GatePassMode.CONTROLLED_BOTH,
            entryCount = 3,
            exitCount = 2,
            emergency = GateEmergencyState.INACTIVE,
            sensors = GateSensorStatus(emptySet(), hasFault = false),
            switches = mapOf(1 to true, 2 to false),
            inputs = mapOf(1 to false),
            observedAt = Clock.System.now(),
        )
}

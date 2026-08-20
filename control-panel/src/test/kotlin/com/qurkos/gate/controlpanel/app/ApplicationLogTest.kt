package com.qurkos.gate.controlpanel.app

import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
}

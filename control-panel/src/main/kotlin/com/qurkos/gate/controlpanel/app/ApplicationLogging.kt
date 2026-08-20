package com.qurkos.gate.controlpanel.app

import com.qurkos.gate.controlpanel.ui.model.EventSeverity
import com.qurkos.gate.controlpanel.ui.model.GateEventUi
import com.qurkos.gate.controlpanel.ui.model.GateTrafficUi
import java.awt.Desktop
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.ConsoleHandler
import java.util.logging.FileHandler
import java.util.logging.Formatter
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/** Configures standard JDK loggers for both the standalone and embedded control-panel launchers. */
internal object ApplicationLogging {
    private val uncaughtHandlerInstalled = AtomicBoolean()
    private val rotatingLogger: RotatingApplicationLogger by lazy {
        RotatingApplicationLogger(resolveApplicationLogDirectory())
    }

    val directory: Path
        get() = rotatingLogger.directory

    fun logger(name: String): Logger = rotatingLogger.logger(name)

    fun logger(owner: Class<*>): Logger = logger(owner.name)

    fun openDirectory() {
        val logDirectory = directory
        if (!openWithDesktop(logDirectory)) {
            val command =
                when {
                    System.getProperty("os.name", "unknown").contains("win", ignoreCase = true) -> "explorer.exe"
                    System.getProperty("os.name", "unknown").contains("mac", ignoreCase = true) -> "open"
                    else -> "xdg-open"
                }
            ProcessBuilder(command, logDirectory.toString()).start()
        }
        logger(ApplicationLogging::class.java).info(
            "Opened application log directory path=${logDirectory.toString().logValue()}",
        )
    }

    fun installUncaughtExceptionHandler() {
        if (!uncaughtHandlerInstalled.compareAndSet(false, true)) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            logger(ApplicationLogging::class.java).log(
                Level.SEVERE,
                "Uncaught exception thread=${thread.name.logValue()}",
                error,
            )
            previous?.uncaughtException(thread, error)
        }
    }

    fun close() {
        if (rotatingLoggerInitialized()) rotatingLogger.close()
    }

    private fun rotatingLoggerInitialized(): Boolean = runCatching { directory }.isSuccess
}

/** Small testable wrapper around the JDK rotating file handler. */
internal class RotatingApplicationLogger(
    val directory: Path,
    maxFileBytes: Int = DEFAULT_MAX_FILE_BYTES,
    retainedFiles: Int = DEFAULT_RETAINED_FILES,
    private val namespace: String = DEFAULT_LOGGER_NAMESPACE,
) : AutoCloseable {
    private val rootLogger = Logger.getLogger(namespace)
    private val fileHandler: FileHandler

    init {
        require(maxFileBytes > 0) { "Maximum log file size must be positive" }
        require(retainedFiles > 0) { "Retained log file count must be positive" }
        Files.createDirectories(directory)
        fileHandler =
            FileHandler(
                directory.toString().replace("%", "%%") + File.separator + LOG_FILE_PATTERN,
                maxFileBytes,
                retainedFiles,
                true,
            ).apply {
                encoding = StandardCharsets.UTF_8.name()
                formatter = ApplicationLogFormatter
                level = Level.ALL
            }
        rootLogger.apply {
            useParentHandlers = false
            level = Level.ALL
            addHandler(fileHandler)
            addHandler(
                ConsoleHandler().apply {
                    formatter = ApplicationLogFormatter
                    level = Level.INFO
                },
            )
        }
        log(Level.INFO, "Persistent logging initialized directory=${directory.toString().logValue()}")
    }

    fun logger(name: String): Logger =
        Logger.getLogger(if (name == namespace || name.startsWith("$namespace.")) name else "$namespace.$name").apply {
            level = Level.ALL
        }

    fun log(
        level: Level,
        message: String,
        error: Throwable? = null,
    ) {
        rootLogger.log(level, message, error)
    }

    override fun close() {
        rootLogger.handlers.forEach { handler ->
            handler.flush()
            handler.close()
            rootLogger.removeHandler(handler)
        }
    }

    private companion object {
        const val DEFAULT_MAX_FILE_BYTES = 5 * 1024 * 1024
        const val DEFAULT_RETAINED_FILES = 7
        const val DEFAULT_LOGGER_NAMESPACE = "com.qurkos.gate.controlpanel"
        const val LOG_FILE_PATTERN = "afc-gate-control-panel-%g.log"
    }
}

internal fun Logger.event(event: GateEventUi) {
    val level =
        when (event.severity) {
            EventSeverity.INFO, EventSeverity.SUCCESS -> Level.INFO
            EventSeverity.WARNING -> Level.WARNING
            EventSeverity.ERROR -> Level.SEVERE
        }
    log(
        level,
        "event category=${event.category.name} severity=${event.severity.name} " +
            "title=${event.title.logValue()} detail=${event.detail.logValue()}",
    )
}

internal fun Logger.traffic(traffic: GateTrafficUi) {
    val level = if (traffic.failed) Level.WARNING else Level.FINE
    log(
        level,
        "traffic direction=${traffic.direction.name} command=${traffic.command.logValue()} " +
            "latencyMs=${traffic.latencyMs ?: "none"} detail=${traffic.detail.logValue()}",
    )
}

internal fun resolveLogDirectory(
    osName: String,
    userHome: Path,
    environment: Map<String, String>,
    overrideDirectory: String?,
): Path {
    overrideDirectory?.takeIf(String::isNotBlank)?.let(Path::of)?.let { return it }
    return when {
        osName.contains("win", ignoreCase = true) -> {
            val localAppData =
                environment["LOCALAPPDATA"]?.takeIf(String::isNotBlank)?.let(Path::of)
                    ?: userHome.resolve("AppData").resolve("Local")
            localAppData.resolve("Qurkos").resolve("AFC Gate Control Panel").resolve("logs")
        }

        osName.contains("mac", ignoreCase = true) ->
            userHome.resolve("Library").resolve("Logs").resolve("AFC Gate Control Panel")

        else -> {
            val stateHome =
                environment["XDG_STATE_HOME"]?.takeIf(String::isNotBlank)?.let(Path::of)
                    ?: userHome.resolve(".local").resolve("state")
            stateHome.resolve("afc-gate-control-panel").resolve("logs")
        }
    }
}

private fun resolveApplicationLogDirectory(): Path =
    resolveLogDirectory(
        osName = System.getProperty("os.name", "unknown"),
        userHome = Path.of(System.getProperty("user.home")),
        environment = System.getenv(),
        overrideDirectory = System.getProperty("afc.gate.log.dir"),
    )

private fun openWithDesktop(directory: Path): Boolean {
    if (!Desktop.isDesktopSupported()) return false
    val desktop = Desktop.getDesktop()
    if (!desktop.isSupported(Desktop.Action.OPEN)) return false
    return runCatching { desktop.open(directory.toFile()) }.isSuccess
}

private object ApplicationLogFormatter : Formatter() {
    private val timestampFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault())

    override fun format(record: LogRecord): String =
        buildString {
            append(timestampFormatter.format(record.instant))
            append(' ')
            append(record.level.name)
            append(' ')
            append(formatMessage(record).singleLine())
            appendLine()
            record.thrown?.let { error ->
                val stackTrace = StringWriter()
                error.printStackTrace(PrintWriter(stackTrace))
                append(stackTrace)
            }
        }
}

private fun String.logValue(): String = "\"${replace("\\", "\\\\").replace("\"", "\\\"").singleLine()}\""

private fun String.singleLine(): String = replace('\r', ' ').replace('\n', ' ')

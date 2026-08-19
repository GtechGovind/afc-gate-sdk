package com.qurkos.gate.sdk.internal

import com.fazecast.jSerialComm.SerialPort
import com.qurkos.gate.sdk.GateError
import com.qurkos.gate.sdk.GateResult
import com.qurkos.gate.sdk.SerialPortInfo
import com.qurkos.gate.sdk.SerialPortName
import com.qurkos.gate.sdk.internal.jvm.JSerialCommTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

@Suppress("InjectDispatcher")
/** Creates the JVM jSerialComm boundary on the blocking-I/O dispatcher. */
internal actual fun createPlatformSerialTransport(): SerialTransport = JSerialCommTransport(Dispatchers.IO)

@Suppress("InjectDispatcher")
/** Uses the default coroutine dispatcher for protocol/session orchestration. */
internal actual fun platformSessionDispatcher(): CoroutineDispatcher = Dispatchers.Default

@Suppress("TooGenericExceptionCaught")
/** Enumerates jSerialComm descriptors while containing every Java/platform exception as a typed result. */
internal actual fun availablePlatformSerialPorts(): GateResult<List<SerialPortInfo>> =
    try {
        GateResult.Success(
            SerialPort.getCommPorts().mapNotNull { port ->
                val name = port.systemPortName?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                SerialPortInfo(
                    name = SerialPortName(name),
                    description = port.descriptivePortName?.takeIf(String::isNotBlank),
                )
            },
        )
    } catch (error: Exception) {
        GateResult.Failure(GateError.Transport(error.message ?: "Unable to enumerate serial ports"))
    }

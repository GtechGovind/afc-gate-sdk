package com.qurkos.gate.sdk

/**
 * Closed hierarchy of expected SDK failures.
 *
 * Operational failures are returned as values so applications can handle serial outages and device rejections without
 * exception-driven control flow. Cancellation and programmer errors are not converted to [GateError].
 */
public sealed interface GateError {
    /** The gate has not been connected. */
    public data object NotConnected : GateError

    /** A command did not receive a response before its deadline. @property operation Safe operation identifier. */
    public data class Timeout(
        /** Safe operation identifier. */
        public val operation: String,
    ) : GateError

    /** Serial transport failure. @property message Platform-neutral diagnostic text. */
    public data class Transport(
        /** Platform-neutral diagnostic text. */
        public val message: String,
    ) : GateError

    /** Malformed or unexpected protocol data. @property message Validation diagnostic. */
    public data class Protocol(
        /** Protocol validation diagnostic. */
        public val message: String,
    ) : GateError

    /**
     * Controller rejection of a valid protocol request.
     *
     * @property code Vendor error code represented as text.
     * @property message Normalized description when the code is known.
     */
    public data class Device(
        public val code: String,
        public val message: String? = null,
    ) : GateError

    /** Invalid typed request. @property message Constraint that was not satisfied. */
    public data class InvalidRequest(
        /** Constraint that was not satisfied. */
        public val message: String,
    ) : GateError

    /** Unsupported operation. @property capability Required unavailable capability. */
    public data class UnsupportedCapability(
        /** Required unavailable capability. */
        public val capability: GateCapability,
    ) : GateError

    /** Missing adapter. @property vendor Explicitly selected unsupported vendor. */
    public data class UnsupportedVendor(
        /** Explicitly selected unsupported vendor. */
        public val vendor: GateVendor,
    ) : GateError
}

/**
 * Result returned by every fallible SDK operation.
 *
 * @param T Successful value type. This type is covariant so failures can be returned from any operation.
 */
public sealed interface GateResult<out T> {
    /** Successful completion. @property value Operation value. */
    public data class Success<T>(
        /** Operation value. */
        public val value: T,
    ) : GateResult<T>

    /** Expected operational failure. @property error Typed failure value. */
    public data class Failure(
        /** Typed failure value. */
        public val error: GateError,
    ) : GateResult<Nothing>
}

/**
 * Transforms a successful value while preserving a typed failure unchanged.
 *
 * Exceptions thrown by [transform] are intentionally not caught because they represent caller/programmer failures.
 */
public inline fun <T, R> GateResult<T>.map(transform: (T) -> R): GateResult<R> =
    when (this) {
        is GateResult.Success -> GateResult.Success(transform(value))
        is GateResult.Failure -> this
    }

/** Handles both result branches exhaustively and returns a single caller-defined value. */
public inline fun <T, R> GateResult<T>.fold(
    onSuccess: (T) -> R,
    onFailure: (GateError) -> R,
): R =
    when (this) {
        is GateResult.Success -> onSuccess(value)
        is GateResult.Failure -> onFailure(error)
    }

/** Returns the successful value, or `null` when this result is [GateResult.Failure]. */
public fun <T> GateResult<T>.getOrNull(): T? = (this as? GateResult.Success)?.value

/** Returns the [GateError], or `null` when this result is [GateResult.Success]. */
public fun GateResult<*>.errorOrNull(): GateError? = (this as? GateResult.Failure)?.error

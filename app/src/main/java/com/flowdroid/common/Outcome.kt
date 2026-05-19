package com.flowdroid.common

/**
 * A typed result of an operation that can fail in a *known* way.
 *
 * Why this exists instead of [kotlin.Result] or raw exceptions:
 *
 *  - Kotlin's [kotlin.Result] doesn't model the failure as a typed sum — the error is a [Throwable].
 *    For an automation app, most failures are expected (permission denied, timeout, validation,
 *    network unreachable). We want the compiler to force callers to handle them.
 *
 *  - Exceptions are reserved for *unexpected* failures (bugs, OOMs, IO that should-never-happen).
 *    Those bubble up to the crash handler and are logged with full stack traces.
 *
 * Guidelines:
 *  - Use [Outcome] for any function whose failure modes are part of its contract.
 *  - Use exceptions for invariant violations and programmer errors.
 *  - Never use `null` as a stand-in for failure when failure has a reason.
 *
 *  Example:
 *  ```
 *  suspend fun postNotification(spec: NotificationSpec): Outcome<NotificationId, PostError> = ...
 *
 *  when (val r = postNotification(spec)) {
 *      is Outcome.Ok -> r.value
 *      is Outcome.Err -> logger.warn("post failed", "reason" to r.error)
 *  }
 *  ```
 */
sealed interface Outcome<out T, out E> {

    data class Ok<out T>(val value: T) : Outcome<T, Nothing>

    data class Err<out E>(val error: E, val cause: Throwable? = null) : Outcome<Nothing, E>

    fun isOk(): Boolean = this is Ok
    fun isErr(): Boolean = this is Err

    fun valueOrNull(): T? = (this as? Ok)?.value
    fun errorOrNull(): E? = (this as? Err)?.error

    companion object {
        fun <T> ok(value: T): Outcome<T, Nothing> = Ok(value)
        fun <E> err(error: E, cause: Throwable? = null): Outcome<Nothing, E> = Err(error, cause)
    }
}

inline fun <T, E, R> Outcome<T, E>.map(transform: (T) -> R): Outcome<R, E> = when (this) {
    is Outcome.Ok -> Outcome.Ok(transform(value))
    is Outcome.Err -> this
}

inline fun <T, E, F> Outcome<T, E>.mapErr(transform: (E) -> F): Outcome<T, F> = when (this) {
    is Outcome.Ok -> this
    is Outcome.Err -> Outcome.Err(transform(error), cause)
}

inline fun <T, E, R> Outcome<T, E>.flatMap(transform: (T) -> Outcome<R, E>): Outcome<R, E> = when (this) {
    is Outcome.Ok -> transform(value)
    is Outcome.Err -> this
}

inline fun <T, E> Outcome<T, E>.onOk(action: (T) -> Unit): Outcome<T, E> = also {
    if (it is Outcome.Ok) action(it.value)
}

inline fun <T, E> Outcome<T, E>.onErr(action: (E, Throwable?) -> Unit): Outcome<T, E> = also {
    if (it is Outcome.Err) action(it.error, it.cause)
}

fun <T, E> Outcome<T, E>.getOrThrow(): T = when (this) {
    is Outcome.Ok -> value
    is Outcome.Err -> throw IllegalStateException("Outcome.Err($error)", cause)
}

/**
 * Run a block, catching any unexpected exception into the supplied error mapper.
 *
 * Use this at *boundaries* where third-party code can throw arbitrary exceptions
 * (Android system services, IO, networking) and we want a typed error in our domain.
 *
 *  ```
 *  outcomeCatching(::IoError) {
 *      file.readText()
 *  }
 *  ```
 */
inline fun <T, E> outcomeCatching(mapError: (Throwable) -> E, block: () -> T): Outcome<T, E> = try {
    Outcome.Ok(block())
} catch (t: Throwable) {
    // Never swallow these — they indicate VM-level problems or coroutine cancellation.
    if (t is OutOfMemoryError || t is kotlin.coroutines.cancellation.CancellationException) throw t
    Outcome.Err(mapError(t), t)
}

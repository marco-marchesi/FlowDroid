package com.flowdroid.engine.actions

import com.flowdroid.common.Outcome
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.ActionExecutor
import com.flowdroid.common.flow.ExecutionContext
import com.flowdroid.common.flow.ExecutionError
import com.flowdroid.common.flow.HttpBodyType
import com.flowdroid.common.flow.HttpMethod
import com.flowdroid.common.flow.MagicTextEngine
import com.flowdroid.common.flow.expandOrEmpty
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Executor for [Action.Http] — the workhorse that enables locked-screen automation against any
 * HTTPS endpoint. Runs entirely in the listener-service coroutine; no UI, no accessibility, no
 * keyguard interaction required.
 *
 * Magic-text expansion applies to URL, header values, and body. So a URL like
 * `https://api.example.com/items/{notification.bigText|regex:items/(\d+)}/subscribe` becomes
 * `…/12345/subscribe` when the trigger event's `bigText` is `…items/12345…`.
 *
 * Per-call timeout: rebuilt from the shared singleton via `newBuilder().callTimeout(...)`. The
 * singleton uses conservative defaults from [com.flowdroid.engine.di.NetworkModule]; the action's
 * own [Action.Http.timeoutMs] tightens further.
 *
 * Result mapping:
 *  - status in expectStatusMin..expectStatusMax → [Outcome.Ok]. Writes `storeStatusInVar` /
 *    `storeBodyInVar` into [ExecutionContext.variables] for the next action.
 *  - status outside range → [ExecutionError.SystemFailure] with a body excerpt.
 *  - [SocketTimeoutException] → [ExecutionError.Timeout].
 *  - [IOException] → [ExecutionError.SystemFailure].
 *
 * Logging never includes the full URL (query strings can carry tokens) or the request/response
 * body (PII). We log `tag="Action.Http"`, method, host, status, latencyMs.
 */
@Singleton
class HttpActionExecutor @Inject constructor(
    private val client: OkHttpClient,
    private val magicText: MagicTextEngine,
    private val logger: StructuredLogger,
) : ActionExecutor<Action.Http> {

    override val actionClass: Class<Action.Http> = Action.Http::class.java

    override suspend fun execute(
        action: Action.Http,
        context: ExecutionContext,
    ): Outcome<Unit, ExecutionError> {
        val resolvedUrl = magicText.expandOrEmpty(action.url, context.variables)
        if (resolvedUrl.isBlank()) {
            return Outcome.err(ExecutionError.InvalidParam("url", "resolved to empty"))
        }
        val host = extractHost(resolvedUrl)

        logger.info(
            TAG, "http request",
            "flowId" to context.flow.id,
            "execId" to context.executionId,
            "method" to action.method.name,
            "host" to host,
        )

        // Build the request.
        val request = try {
            buildRequest(action, resolvedUrl, context)
        } catch (t: Throwable) {
            if (t is OutOfMemoryError ||
                t is kotlin.coroutines.cancellation.CancellationException
            ) throw t
            logger.warn(TAG, "request build failed", t,
                "flowId" to context.flow.id, "execId" to context.executionId)
            return Outcome.err(ExecutionError.InvalidParam("request", t.message ?: "invalid"))
        }

        // Per-call client with the action's timeout.
        val perCallClient = client.newBuilder()
            .callTimeout(action.timeoutMs, TimeUnit.MILLISECONDS)
            .build()

        // Retries are bounded by Action.Http.MAX_RETRIES to keep total wall-time inside the
        // engine's 30s per-action ceiling. retries=0 (default) preserves the legacy single-shot
        // behaviour exactly — no behaviour change for existing flows.
        val maxRetries = action.retries.coerceIn(0, Action.Http.MAX_RETRIES)
        var attempt = 0
        var lastResult: Outcome<Unit, ExecutionError>
        while (true) {
            lastResult = executeOnce(action, request, perCallClient, host, context, attempt)
            // Stop on success.
            if (lastResult is Outcome.Ok) return lastResult
            // Stop if no retries left.
            if (attempt >= maxRetries) return lastResult
            // Stop if the failure is non-retryable.
            if (!shouldRetry(action, lastResult, context)) return lastResult

            val backoffMs = (action.retryBackoffMs.coerceAtLeast(0L) shl attempt)
                .coerceAtMost(Action.Http.MAX_BACKOFF_MS)
            logger.info(
                TAG, "http retry scheduled",
                "flowId" to context.flow.id,
                "execId" to context.executionId,
                "host" to host,
                "attempt" to (attempt + 1),
                "maxRetries" to maxRetries,
                "backoffMs" to backoffMs,
            )
            delay(backoffMs)
            attempt++
        }
    }

    /**
     * One HTTP attempt. Returns Ok on a status inside `expectStatus*`, else a typed error. Network
     * errors map to [ExecutionError.Timeout] / [ExecutionError.SystemFailure] exactly as before —
     * the retry loop in [execute] decides whether to try again.
     */
    private suspend fun executeOnce(
        action: Action.Http,
        request: Request,
        perCallClient: OkHttpClient,
        host: String,
        context: ExecutionContext,
        attempt: Int,
    ): Outcome<Unit, ExecutionError> {
        val startedAt = System.currentTimeMillis()
        return try {
            perCallClient.newCall(request).awaitResponse().use { response ->
                val latency = System.currentTimeMillis() - startedAt
                val status = response.code
                val bodyText = response.body?.string().orEmpty()

                logger.info(
                    TAG, "http response",
                    "flowId" to context.flow.id,
                    "execId" to context.executionId,
                    "method" to action.method.name,
                    "host" to host,
                    "status" to status,
                    "latencyMs" to latency,
                    "attempt" to attempt,
                )

                // Store status + body in vars even on non-2xx — useful for "fail soft" flows.
                // Retries overwrite, so the final attempt's values win — that matches what users
                // expect to see in the next action.
                action.storeStatusInVar?.takeIf { it.isNotBlank() }?.let {
                    context.variables[it] = status.toString()
                }
                action.storeBodyInVar?.takeIf { it.isNotBlank() }?.let {
                    context.variables[it] = bodyText
                }

                if (status in action.expectStatusMin..action.expectStatusMax) {
                    Outcome.ok(Unit)
                } else {
                    Outcome.err(
                        ExecutionError.SystemFailure(
                            "HTTP $status: ${bodyText.take(200)}",
                        ),
                    )
                }
            }
        } catch (e: SocketTimeoutException) {
            logger.warn(TAG, "http timeout", e,
                "flowId" to context.flow.id, "host" to host, "attempt" to attempt)
            Outcome.err(ExecutionError.Timeout(action.timeoutMs), e)
        } catch (e: IOException) {
            logger.warn(TAG, "http io error", e,
                "flowId" to context.flow.id, "host" to host, "attempt" to attempt)
            Outcome.err(ExecutionError.SystemFailure(e.message ?: "io error"), e)
        } catch (t: Throwable) {
            if (t is OutOfMemoryError ||
                t is kotlin.coroutines.cancellation.CancellationException
            ) throw t
            logger.warn(TAG, "http unexpected error", t,
                "flowId" to context.flow.id, "host" to host, "attempt" to attempt)
            Outcome.err(ExecutionError.SystemFailure(t.message ?: "unknown"), t)
        }
    }

    /**
     * Decide whether a failed attempt is worth retrying.
     *  - Network-layer errors ([ExecutionError.Timeout] + [ExecutionError.SystemFailure] without a
     *    captured status) → retry.
     *  - Status-code errors → retry only when the status sits inside `retryOnStatus*`. This is
     *    inferred by reading back `storeStatusInVar` if the user wired it, otherwise inferred by
     *    parsing the error message ("HTTP %d: ...").
     */
    private fun shouldRetry(
        action: Action.Http,
        outcome: Outcome<Unit, ExecutionError>,
        context: ExecutionContext,
    ): Boolean {
        if (outcome !is Outcome.Err) return false
        val err = outcome.error
        return when (err) {
            is ExecutionError.Timeout -> true
            is ExecutionError.SystemFailure -> {
                val status = parseStatusFromMessage(err.message)
                    ?: run {
                        // No status parsed → assume network-layer error; retry.
                        return true
                    }
                status in action.retryOnStatusMin..action.retryOnStatusMax
            }
            else -> false
        }
    }

    private fun parseStatusFromMessage(message: String?): Int? {
        if (message.isNullOrBlank()) return null
        // Format produced above: "HTTP <code>: …".
        val prefix = "HTTP "
        if (!message.startsWith(prefix)) return null
        val rest = message.substring(prefix.length)
        val colon = rest.indexOf(':')
        val candidate = if (colon < 0) rest else rest.substring(0, colon)
        return candidate.trim().toIntOrNull()
    }

    private fun buildRequest(
        action: Action.Http,
        resolvedUrl: String,
        context: ExecutionContext,
    ): Request {
        val builder = Request.Builder().url(resolvedUrl)

        // Headers — preserve order, magic-text-expand values.
        for (header in action.headers) {
            val resolvedValue = magicText.expandOrEmpty(header.value, context.variables)
            builder.addHeader(header.name, resolvedValue)
        }

        // Body. GET/DELETE may not have a body; PUT/POST/PATCH usually do.
        val rawBody = action.body
        val needsBody = action.method == HttpMethod.POST ||
            action.method == HttpMethod.PUT ||
            action.method == HttpMethod.PATCH
        val resolvedBody = if (rawBody != null) magicText.expandOrEmpty(rawBody, context.variables) else null
        val body: RequestBody? = when {
            !needsBody -> null
            resolvedBody == null -> null
            action.bodyContentType == HttpBodyType.NONE ->
                resolvedBody.toRequestBody(null)
            action.bodyContentType == HttpBodyType.JSON ->
                resolvedBody.toRequestBody("application/json; charset=utf-8".toMediaType())
            action.bodyContentType == HttpBodyType.TEXT ->
                resolvedBody.toRequestBody("text/plain; charset=utf-8".toMediaType())
            action.bodyContentType == HttpBodyType.FORM -> buildFormBody(resolvedBody)
            else -> null
        }

        when (action.method) {
            HttpMethod.GET -> builder.get()
            HttpMethod.DELETE -> if (body != null) builder.delete(body) else builder.delete()
            HttpMethod.POST -> builder.post(body ?: emptyBody())
            HttpMethod.PUT -> builder.put(body ?: emptyBody())
            HttpMethod.PATCH -> builder.patch(body ?: emptyBody())
        }
        return builder.build()
    }

    /**
     * Parse `key=value&key2=value2` into a [FormBody]. Already-URL-encoded values are accepted
     * verbatim; unencoded values are forwarded as-is (OkHttp will encode on send).
     */
    private fun buildFormBody(raw: String): FormBody {
        val builder = FormBody.Builder()
        if (raw.isBlank()) return builder.build()
        for (pair in raw.split('&')) {
            val eq = pair.indexOf('=')
            if (eq < 0) {
                builder.add(pair, "")
            } else {
                builder.add(pair.substring(0, eq), pair.substring(eq + 1))
            }
        }
        return builder.build()
    }

    private fun emptyBody(): RequestBody = "".toRequestBody(null)

    private fun extractHost(url: String): String = try {
        java.net.URL(url).host ?: "<invalid>"
    } catch (t: Throwable) {
        if (t is OutOfMemoryError) throw t
        "<invalid>"
    }

    /**
     * Suspend wrapper around `Call.enqueue` that propagates cancellation. We don't use OkHttp's
     * coroutine extension lib to keep transitive deps minimal.
     */
    private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response)
            }
        })
        cont.invokeOnCancellation {
            try { cancel() } catch (_: Throwable) { /* best-effort */ }
        }
    }

    companion object {
        private const val TAG = "Action.Http"
    }
}

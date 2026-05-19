package com.flowdroid.platform.webhook

import android.content.Context
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.flow.FlowEngine
import com.flowdroid.common.flow.Trigger
import com.flowdroid.common.flow.TriggerEvent
import com.flowdroid.common.flow.WebhookMethod
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Embedded HTTP server that turns inbound HTTP requests on `127.0.0.1:8821/hook/<path>` into
 * [TriggerEvent.WebhookReceived] dispatches against [FlowEngine.onTriggered].
 *
 * Lifecycle:
 *  - [register]/[unregister] mutate an in-memory registry keyed by the path component
 *    (everything after `/hook/`).
 *  - [startIfNeeded] binds the underlying NanoHTTPD when the registry is non-empty, or stops
 *    it when the registry has been drained. Both directions are idempotent.
 *
 * Threading:
 *  - NanoHTTPD spawns its own worker thread per request; we cross back into coroutine land via
 *    `runBlocking(Dispatchers.IO)` for the engine dispatch — safe because the calling worker is
 *    already pooled and short-lived.
 *
 * Security / privacy:
 *  - Constant-time secret comparison; never log body or secret values.
 *  - Logs include method, path, status, latencyMs, content-length (no payload).
 */
@Singleton
class WebhookServer(
    @ApplicationContext private val context: Context,
    private val flowEngine: FlowEngine,
    private val logger: StructuredLogger,
    private val port: Int = DEFAULT_PORT,
) {

    /**
     * Hilt-friendly constructor. Hilt does not honour Kotlin default-value parameters, so the
     * production graph injects the no-port form and the primary constructor's default applies.
     * Tests instantiate the primary constructor directly to bind on an OS-assigned free port.
     */
    @Inject constructor(
        @ApplicationContext context: Context,
        flowEngine: FlowEngine,
        logger: StructuredLogger,
    ) : this(context, flowEngine, logger, DEFAULT_PORT)

    private val registry = ConcurrentHashMap<String, WebhookEntry>()

    @Volatile
    private var server: NanoServer? = null

    /** Register a webhook trigger for [flowId]. Idempotent on path; later registrations overwrite. */
    fun register(flowId: String, trigger: Trigger.Webhook) {
        registry[trigger.path] = WebhookEntry(
            flowId = flowId,
            method = trigger.method,
            secret = trigger.secret,
        )
        logger.info(
            TAG, "registered webhook",
            "flowId" to flowId, "path" to trigger.path, "method" to trigger.method,
        )
    }

    /** Remove all entries belonging to [flowId]. No-op if none. */
    fun unregister(flowId: String) {
        val removed = registry.entries.removeAll { it.value.flowId == flowId }
        if (removed) {
            logger.info(TAG, "unregistered webhooks", "flowId" to flowId)
        }
    }

    /** Drop every registration. The server is not stopped here — call [startIfNeeded] for that. */
    fun unregisterAll() {
        registry.clear()
    }

    /** True when the embedded HTTP listener is currently bound. */
    fun isBound(): Boolean = server?.wasStarted() == true

    /**
     * Bind the server if at least one entry is registered and we aren't already bound; or stop
     * the server if no entries remain. Safe to call repeatedly.
     */
    @Suppress("RedundantSuspendModifier") // matches plan signature; allows future async work
    suspend fun startIfNeeded() {
        val empty = registry.isEmpty()
        val bound = isBound()
        when {
            empty && bound -> stopInternal()
            !empty && !bound -> startInternal()
            else -> Unit
        }
    }

    private fun startInternal() {
        try {
            val s = NanoServer(port)
            s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, /* daemon = */ true)
            server = s
            logger.info(TAG, "server bound", "port" to port, "entries" to registry.size)
        } catch (oom: OutOfMemoryError) {
            throw oom
        } catch (t: Throwable) {
            logger.error(TAG, "server bind failed", t, "port" to port)
        }
    }

    private fun stopInternal() {
        try {
            server?.stop()
            logger.info(TAG, "server stopped", "port" to port)
        } catch (oom: OutOfMemoryError) {
            throw oom
        } catch (t: Throwable) {
            logger.warn(TAG, "server stop failed", t)
        } finally {
            server = null
        }
    }

    /** Internal NanoHTTPD subclass — kept private so callers can't poke at its low-level API. */
    private inner class NanoServer(port: Int) : NanoHTTPD("127.0.0.1", port) {

        override fun serve(session: IHTTPSession): Response {
            val started = System.nanoTime()
            val uri = session.uri ?: ""
            val method = session.method?.name ?: "GET"
            return try {
                handle(session, uri, method, started)
            } catch (oom: OutOfMemoryError) {
                throw oom
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                logger.error(TAG, "request failed", t, "uri" to uri, "method" to method)
                // Diagnostic — surface to stderr so test runners capture it. Stripped by R8 in release.
                if (com.flowdroid.BuildConfig.DEBUG) t.printStackTrace()
                jsonResponse(Response.Status.INTERNAL_ERROR, """{"error":"internal_error"}""")
            }
        }

        private fun handle(
            session: IHTTPSession,
            uri: String,
            method: String,
            startedNanos: Long,
        ): Response {
            if (!uri.startsWith(PREFIX)) {
                return logged(method, uri, startedNanos, 0,
                    jsonResponse(Response.Status.NOT_FOUND, """{"error":"not_found"}"""))
            }
            val path = uri.removePrefix(PREFIX)
            val entry = registry[path]
                ?: return logged(method, uri, startedNanos, 0,
                    jsonResponse(Response.Status.NOT_FOUND, """{"error":"not_found"}"""))

            if (entry.method != WebhookMethod.ANY && !methodMatches(entry.method, method)) {
                return logged(method, uri, startedNanos, 0,
                    jsonResponse(Response.Status.METHOD_NOT_ALLOWED, """{"error":"method_not_allowed"}"""))
            }

            if (entry.secret != null) {
                val supplied = lookupHeaderCaseInsensitive(session.headers, SECRET_HEADER)
                if (supplied == null || !constantTimeEquals(supplied, entry.secret)) {
                    return logged(method, uri, startedNanos, 0,
                        jsonResponse(Response.Status.UNAUTHORIZED, """{"error":"unauthorized"}"""))
                }
            }

            // Read body with a 1 MB cap.
            val bodyBytes = try {
                readBodyCapped(session)
            } catch (oom: OutOfMemoryError) {
                throw oom
            } catch (tooBig: BodyTooLargeException) {
                return logged(method, uri, startedNanos, tooBig.readSoFar,
                    jsonResponse(
                        Response.Status.lookup(413) ?: Response.Status.INTERNAL_ERROR,
                        """{"error":"payload_too_large"}""",
                    ))
            } catch (t: Throwable) {
                if (t is OutOfMemoryError ||
                    t is kotlin.coroutines.cancellation.CancellationException
                ) throw t
                logger.warn(TAG, "body read failed", t, "uri" to uri)
                if (com.flowdroid.BuildConfig.DEBUG) {
                    System.err.println("[WebhookServer] body read threw:")
                    t.printStackTrace()
                }
                return logged(method, uri, startedNanos, 0,
                    jsonResponse(Response.Status.INTERNAL_ERROR, """{"error":"read_failed"}"""))
            }
            val body = bodyBytes.toString(Charsets.UTF_8)

            val headers = session.headers.orEmpty()
                .mapKeys { it.key.lowercase() }
                .toMap()

            // session.parameters: Map<String, List<String>> — flatten to first value per key.
            val params: Map<String, List<String>> = session.parameters.orEmpty()
            val query = params.mapValues { it.value.firstOrNull().orEmpty() }

            val event = TriggerEvent.WebhookReceived(
                method = method,
                path = path,
                headers = headers,
                query = query,
                body = body,
            )

            try {
                runBlocking(Dispatchers.IO) {
                    flowEngine.onTriggered(entry.flowId, event)
                }
            } catch (oom: OutOfMemoryError) {
                throw oom
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                if (t is OutOfMemoryError ||
                    t is kotlin.coroutines.cancellation.CancellationException
                ) throw t
                logger.error(TAG, "engine dispatch failed", t,
                    "flowId" to entry.flowId, "path" to path)
                if (com.flowdroid.BuildConfig.DEBUG) {
                    System.err.println("[WebhookServer] dispatch threw:")
                    t.printStackTrace()
                }
                return logged(method, uri, startedNanos, bodyBytes.size,
                    jsonResponse(Response.Status.INTERNAL_ERROR, """{"error":"dispatch_failed"}"""))
            }

            val ok = jsonResponse(
                Response.Status.OK,
                """{"flowId":"${entry.flowId}","status":"queued"}""",
            )
            return logged(method, uri, startedNanos, bodyBytes.size, ok)
        }

        private fun logged(
            method: String,
            uri: String,
            startedNanos: Long,
            contentLength: Int,
            response: Response,
        ): Response {
            val latencyMs = (System.nanoTime() - startedNanos) / 1_000_000
            logger.info(
                TAG, "served",
                "method" to method,
                "path" to uri,
                "status" to response.status.requestStatus,
                "latencyMs" to latencyMs,
                "contentLength" to contentLength,
            )
            return response
        }
    }

    private class BodyTooLargeException(val readSoFar: Int) : RuntimeException()

    private fun readBodyCapped(session: NanoHTTPD.IHTTPSession): ByteArray {
        val declared = session.headers?.get("content-length")?.toIntOrNull() ?: -1
        if (declared > MAX_BODY_BYTES) throw BodyTooLargeException(declared)
        // Two strict policies that together prevent socket-read deadlocks:
        //  1. NanoHTTPD returns a null inputStream for methods without a body (HEAD, etc.) —
        //     short-circuit.
        //  2. If Content-Length is missing (-1) or zero, we don't attempt to read any bytes.
        //     Reading from the socket without Content-Length blocks until EOF, which never
        //     comes because the client is waiting for our response — classic deadlock.
        //     Chunked-Transfer requests are rare for this surface and not supported here.
        if (declared <= 0) return ByteArray(0)
        val input: InputStream = session.inputStream ?: return ByteArray(0)
        val buf = ByteArrayOutputStream(minOf(declared, 8192).coerceAtLeast(64))
        val chunk = ByteArray(8192)
        var total = 0
        while (total < declared) {
            val want = minOf(chunk.size, declared - total)
            val n = input.read(chunk, 0, want)
            if (n <= 0) break
            buf.write(chunk, 0, n)
            total += n
        }
        if (total > MAX_BODY_BYTES) throw BodyTooLargeException(total)
        return buf.toByteArray()
    }

    private fun methodMatches(allowed: WebhookMethod, requestMethod: String): Boolean = when (allowed) {
        WebhookMethod.GET -> requestMethod.equals("GET", ignoreCase = true)
        WebhookMethod.POST -> requestMethod.equals("POST", ignoreCase = true)
        WebhookMethod.PUT -> requestMethod.equals("PUT", ignoreCase = true)
        WebhookMethod.ANY -> true
    }

    private fun lookupHeaderCaseInsensitive(headers: Map<String, String>?, key: String): String? {
        if (headers == null) return null
        val lk = key.lowercase()
        for ((k, v) in headers) if (k.lowercase() == lk) return v
        return null
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val ab = a.toByteArray(Charsets.UTF_8)
        val bb = b.toByteArray(Charsets.UTF_8)
        if (ab.size != bb.size) return false
        var diff = 0
        for (i in ab.indices) diff = diff or (ab[i].toInt() xor bb[i].toInt())
        return diff == 0
    }

    private fun jsonResponse(status: NanoHTTPD.Response.IStatus, body: String): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(status, "application/json", body)

    companion object {
        private const val TAG = "Webhook"
        private const val PREFIX = "/hook/"
        private const val SECRET_HEADER = "X-FlowDroid-Secret"
        const val DEFAULT_PORT = 8821
        const val MAX_BODY_BYTES = 1 * 1024 * 1024 // 1 MB
    }
}

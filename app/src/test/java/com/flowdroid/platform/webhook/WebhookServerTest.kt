package com.flowdroid.platform.webhook

import android.content.Context
import com.flowdroid.common.TimberStructuredLogger
import com.flowdroid.common.flow.FlowEngine
import com.flowdroid.common.flow.Trigger
import com.flowdroid.common.flow.TriggerEvent
import com.flowdroid.common.flow.WebhookMethod
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

class WebhookServerTest {

    private val context: Context = mockk(relaxed = true)
    private val engine: FlowEngine = mockk()
    private val logger = TimberStructuredLogger()

    private lateinit var server: WebhookServer
    private var port: Int = 0
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @BeforeEach fun setUp() {
        coEvery { engine.onTriggered(any(), any()) } returns Unit
        port = freePort()
        server = WebhookServer(context, engine, logger, port)
    }

    @AfterEach fun tearDown() = runBlocking {
        server.unregisterAll()
        server.startIfNeeded() // drains -> stops
    }

    private fun urlFor(path: String): String = "http://127.0.0.1:$port/hook/$path"

    private fun start(): Unit = runBlocking { server.startIfNeeded() }

    @Test fun `registered POST flow dispatches engine onTriggered with event payload`() = runBlocking {
        server.register("flow-1", Trigger.Webhook(path = "abc", method = WebhookMethod.POST))
        start()

        val resp = client.newCall(
            Request.Builder()
                .url(urlFor("abc") + "?foo=bar&baz=qux")
                .post("""{"hello":"world"}""".toRequestBody("application/json".toMediaType()))
                .header("X-Custom", "v1")
                .build()
        ).execute()

        resp.use {
            assertThat(it.code).isEqualTo(200)
            assertThat(it.body?.string()).contains("\"flowId\":\"flow-1\"")
        }

        val captured = slot<TriggerEvent>()
        coVerify(exactly = 1) { engine.onTriggered("flow-1", capture(captured)) }
        val ev = captured.captured as TriggerEvent.WebhookReceived
        assertThat(ev.method).isEqualTo("POST")
        assertThat(ev.path).isEqualTo("abc")
        assertThat(ev.body).isEqualTo("""{"hello":"world"}""")
        assertThat(ev.headers["x-custom"]).isEqualTo("v1")
        assertThat(ev.query["foo"]).isEqualTo("bar")
        assertThat(ev.query["baz"]).isEqualTo("qux")
    }

    @Test fun `unknown path returns 404`() = runBlocking {
        server.register("flow-1", Trigger.Webhook(path = "known"))
        start()

        val resp = client.newCall(Request.Builder().url(urlFor("missing")).get().build()).execute()
        resp.use {
            assertThat(it.code).isEqualTo(404)
        }
        coVerify(exactly = 0) { engine.onTriggered(any(), any()) }
    }

    @Test fun `wrong secret returns 401`() = runBlocking {
        server.register("flow-1", Trigger.Webhook(path = "abc", secret = "topsecret"))
        start()

        val resp = client.newCall(
            Request.Builder()
                .url(urlFor("abc"))
                .header("X-FlowDroid-Secret", "wrong")
                .post(ByteArray(0).toRequestBody())
                .build()
        ).execute()
        resp.use { assertThat(it.code).isEqualTo(401) }
        coVerify(exactly = 0) { engine.onTriggered(any(), any()) }
    }

    @Test fun `missing secret header returns 401 when secret required`() = runBlocking {
        server.register("flow-1", Trigger.Webhook(path = "abc", secret = "topsecret"))
        start()

        val resp = client.newCall(
            Request.Builder().url(urlFor("abc")).post(ByteArray(0).toRequestBody()).build()
        ).execute()
        resp.use { assertThat(it.code).isEqualTo(401) }
        coVerify(exactly = 0) { engine.onTriggered(any(), any()) }
    }

    @Test fun `method mismatch returns 405`() = runBlocking {
        server.register("flow-1", Trigger.Webhook(path = "abc", method = WebhookMethod.POST))
        start()

        val resp = client.newCall(Request.Builder().url(urlFor("abc")).get().build()).execute()
        resp.use { assertThat(it.code).isEqualTo(405) }
        coVerify(exactly = 0) { engine.onTriggered(any(), any()) }
    }

    @Test fun `method ANY accepts any verb`() = runBlocking {
        server.register("flow-1", Trigger.Webhook(path = "abc", method = WebhookMethod.ANY))
        start()

        val get = client.newCall(Request.Builder().url(urlFor("abc")).get().build()).execute()
        get.use { assertThat(it.code).isEqualTo(200) }

        val put = client.newCall(
            Request.Builder().url(urlFor("abc")).put(ByteArray(0).toRequestBody()).build()
        ).execute()
        put.use { assertThat(it.code).isEqualTo(200) }

        val post = client.newCall(
            Request.Builder().url(urlFor("abc")).post("x".toRequestBody()).build()
        ).execute()
        post.use { assertThat(it.code).isEqualTo(200) }

        coVerify(exactly = 3) { engine.onTriggered("flow-1", any()) }
    }

    @Test fun `body headers and query are forwarded in event`() = runBlocking {
        server.register("flow-1", Trigger.Webhook(path = "h", method = WebhookMethod.ANY))
        start()

        val resp = client.newCall(
            Request.Builder()
                .url(urlFor("h") + "?a=1&b=two")
                .header("X-Foo", "Bar")
                .post("payload-data".toRequestBody("text/plain".toMediaType()))
                .build()
        ).execute()
        resp.use { assertThat(it.code).isEqualTo(200) }

        val captured = slot<TriggerEvent>()
        coVerify { engine.onTriggered("flow-1", capture(captured)) }
        val ev = captured.captured as TriggerEvent.WebhookReceived
        assertThat(ev.body).isEqualTo("payload-data")
        assertThat(ev.headers["x-foo"]).isEqualTo("Bar")
        assertThat(ev.query["a"]).isEqualTo("1")
        assertThat(ev.query["b"]).isEqualTo("two")
    }

    @Test fun `unregister flow removes path - subsequent requests 404`() = runBlocking {
        server.register("flow-1", Trigger.Webhook(path = "abc"))
        start()

        val ok = client.newCall(Request.Builder().url(urlFor("abc")).get().build()).execute()
        ok.use { assertThat(it.code).isEqualTo(200) }

        server.unregister("flow-1")

        val gone = client.newCall(Request.Builder().url(urlFor("abc")).get().build()).execute()
        gone.use { assertThat(it.code).isEqualTo(404) }
    }

    @Test fun `body larger than 1 MB returns 413`() = runBlocking {
        server.register("flow-1", Trigger.Webhook(path = "big", method = WebhookMethod.POST))
        start()

        val big = ByteArray(WebhookServer.MAX_BODY_BYTES + 1024) { 'a'.code.toByte() }
        val resp = client.newCall(
            Request.Builder()
                .url(urlFor("big"))
                .post(big.toRequestBody("application/octet-stream".toMediaType()))
                .build()
        ).execute()
        resp.use { assertThat(it.code).isEqualTo(413) }
        coVerify(exactly = 0) { engine.onTriggered(any(), any()) }
    }
}

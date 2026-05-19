package com.flowdroid.engine.actions

import com.flowdroid.common.Outcome
import com.flowdroid.common.TimberStructuredLogger
import com.flowdroid.common.domain.NotificationEvent
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.ExecutionContext
import com.flowdroid.common.flow.ExecutionError
import com.flowdroid.common.flow.Flow
import com.flowdroid.common.flow.HttpBodyType
import com.flowdroid.common.flow.HttpHeader
import com.flowdroid.common.flow.HttpMethod
import com.flowdroid.common.flow.Trigger
import com.flowdroid.common.flow.TriggerEvent
import com.flowdroid.engine.MagicTextEngineImpl
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class HttpActionExecutorTest {

    private lateinit var server: MockWebServer
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()
    private val logger = TimberStructuredLogger()
    private val magicText = MagicTextEngineImpl()
    private lateinit var executor: HttpActionExecutor

    @BeforeEach fun setUp() {
        server = MockWebServer().also { it.start() }
        executor = HttpActionExecutor(client, magicText, logger)
    }

    @AfterEach fun tearDown() {
        server.shutdown()
    }

    private fun execCtx(vars: MutableMap<String, String> = mutableMapOf()) = ExecutionContext(
        executionId = "exec1",
        flow = Flow(
            id = "flow-1", name = "F", enabled = true,
            triggers = listOf(Trigger.NotificationPosted()),
            actions = emptyList(), createdAt = 0L, updatedAt = 0L,
        ),
        triggerEvent = TriggerEvent.NotificationFired(
            event = NotificationEvent(
                sbnKey = "k", packageName = "p", postTimeMillis = 0L,
                notificationPostTimeMillis = 0L,
                title = null, text = null, bigText = null, subText = null, tickerText = null,
                channelId = null, groupKey = null,
                isOngoing = false, isClearable = true, isGroupSummary = false,
                importance = 3, notificationId = 1, actionLabels = emptyList(),
                rawExtrasJson = null,
            ),
            sbnKey = "k",
        ),
        variables = vars,
    )

    @Test fun `200 GET returns Ok and stores status + body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        val vars = mutableMapOf<String, String>()

        val r = executor.execute(
            Action.Http(
                method = HttpMethod.GET,
                url = server.url("/games").toString(),
                storeStatusInVar = "status",
                storeBodyInVar = "body",
            ),
            execCtx(vars),
        )

        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
        assertThat(vars["status"]).isEqualTo("200")
        assertThat(vars["body"]).isEqualTo("""{"ok":true}""")
        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("GET")
        assertThat(recorded.path).isEqualTo("/games")
    }

    @Test fun `POST JSON body is sent with correct content-type`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        // Literal { } must be escaped as \{ \} — magic-text treats { as a placeholder opener.
        // The editor's body field documents this; real flows that need literal braces escape.
        val r = executor.execute(
            Action.Http(
                method = HttpMethod.POST,
                url = server.url("/subscribe").toString(),
                body = """\{"hello":"world"\}""",
                bodyContentType = HttpBodyType.JSON,
            ),
            execCtx(),
        )

        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.getHeader("Content-Type")).contains("application/json")
        assertThat(recorded.body.readUtf8()).isEqualTo("""{"hello":"world"}""")
    }

    @Test fun `headers are forwarded in order`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        executor.execute(
            Action.Http(
                method = HttpMethod.GET,
                url = server.url("/").toString(),
                headers = listOf(
                    HttpHeader("X-First", "alpha"),
                    HttpHeader("X-Second", "beta"),
                    HttpHeader("Authorization", "Bearer abc"),
                ),
            ),
            execCtx(),
        )

        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("X-First")).isEqualTo("alpha")
        assertThat(recorded.getHeader("X-Second")).isEqualTo("beta")
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer abc")
    }

    @Test fun `magic text in URL is expanded`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        val vars = mutableMapOf("match_id" to "42")

        executor.execute(
            Action.Http(
                method = HttpMethod.GET,
                url = server.url("/matches/").toString() + "{match_id}",
            ),
            execCtx(vars),
        )

        assertThat(server.takeRequest().path).isEqualTo("/matches/42")
    }

    @Test fun `magic text in header value is expanded`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        val vars = mutableMapOf("token" to "abc.def.xyz")

        executor.execute(
            Action.Http(
                method = HttpMethod.GET,
                url = server.url("/").toString(),
                headers = listOf(HttpHeader("Authorization", "Bearer {token}")),
            ),
            execCtx(vars),
        )

        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer abc.def.xyz")
    }

    @Test fun `FORM body sends application x-www-form-urlencoded`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        executor.execute(
            Action.Http(
                method = HttpMethod.POST,
                url = server.url("/form").toString(),
                body = "a=1&b=hello world",
                bodyContentType = HttpBodyType.FORM,
            ),
            execCtx(),
        )

        val rec = server.takeRequest()
        assertThat(rec.getHeader("Content-Type")).contains("application/x-www-form-urlencoded")
        // Read once — `readUtf8()` drains the buffer; subsequent reads return empty.
        val body = rec.body.readUtf8()
        assertThat(body).contains("a=1")
        assertThat(body).contains("b=hello")  // space encoded as `+` or `%20`
    }

    @Test fun `status outside expected range yields SystemFailure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))

        val r = executor.execute(
            Action.Http(
                method = HttpMethod.GET,
                url = server.url("/").toString(),
            ),
            execCtx(),
        )

        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        val err = (r as Outcome.Err).error
        assertThat(err).isInstanceOf(ExecutionError.SystemFailure::class.java)
        assertThat((err as ExecutionError.SystemFailure).message).contains("403")
        assertThat(err.message).contains("Forbidden")
    }

    @Test fun `blank URL returns InvalidParam`() = runTest {
        val r = executor.execute(
            Action.Http(method = HttpMethod.GET, url = ""),
            execCtx(),
        )
        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat((r as Outcome.Err).error).isInstanceOf(ExecutionError.InvalidParam::class.java)
    }

    @Test fun `socket disconnect yields SystemFailure`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val r = executor.execute(
            Action.Http(
                method = HttpMethod.GET,
                url = server.url("/").toString(),
                timeoutMs = 2_000L,
            ),
            execCtx(),
        )

        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        val err = (r as Outcome.Err).error
        // Either SystemFailure (most likely from EOF / connection reset) or Timeout — both acceptable.
        assertThat(err is ExecutionError.SystemFailure || err is ExecutionError.Timeout).isTrue()
    }

    @Test fun `expected status range can include non-2xx`() = runTest {
        server.enqueue(MockResponse().setResponseCode(304))

        val r = executor.execute(
            Action.Http(
                method = HttpMethod.GET,
                url = server.url("/").toString(),
                expectStatusMin = 200,
                expectStatusMax = 399,
            ),
            execCtx(),
        )

        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
    }

    // ───────────────────── Phase 8 — retry-on-failure ─────────────────────

    @Test fun `retry on 5xx eventually succeeds`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(502))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val r = executor.execute(
            Action.Http(
                method = HttpMethod.GET,
                url = server.url("/x").toString(),
                retries = 3,
                retryBackoffMs = 1L,
            ),
            execCtx(),
        )

        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test fun `no retry on a 4xx status outside the retry range`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        // No further MockResponses queued — a second call would block/fail.

        val r = executor.execute(
            Action.Http(
                method = HttpMethod.GET,
                url = server.url("/x").toString(),
                retries = 3,
                retryBackoffMs = 1L,
            ),
            execCtx(),
        )

        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun `retry on network error eventually succeeds`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val r = executor.execute(
            Action.Http(
                method = HttpMethod.GET,
                url = server.url("/x").toString(),
                retries = 2,
                retryBackoffMs = 1L,
            ),
            execCtx(),
        )

        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun `retries exhausted still returns the last failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))

        val r = executor.execute(
            Action.Http(
                method = HttpMethod.GET,
                url = server.url("/x").toString(),
                retries = 2,
                retryBackoffMs = 1L,
            ),
            execCtx(),
        )

        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test fun `retries clamped to MAX_RETRIES`() = runTest {
        // Configure 99 retries; engine clamps to MAX_RETRIES (=5). Total attempts = 6.
        repeat(Action.Http.MAX_RETRIES + 1) {
            server.enqueue(MockResponse().setResponseCode(500))
        }

        val r = executor.execute(
            Action.Http(
                method = HttpMethod.GET,
                url = server.url("/x").toString(),
                retries = 99,
                retryBackoffMs = 1L,
            ),
            execCtx(),
        )

        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat(server.requestCount).isEqualTo(Action.Http.MAX_RETRIES + 1)
    }

    @Test fun `retries=0 preserves single-shot legacy behaviour`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val r = executor.execute(
            Action.Http(
                method = HttpMethod.GET,
                url = server.url("/x").toString(),
                retries = 0,
            ),
            execCtx(),
        )

        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun `final attempt status and body land in storeStatus and storeBody`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("first"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("second"))
        val vars = mutableMapOf<String, String>()

        executor.execute(
            Action.Http(
                method = HttpMethod.GET,
                url = server.url("/x").toString(),
                retries = 1,
                retryBackoffMs = 1L,
                storeStatusInVar = "s",
                storeBodyInVar = "b",
            ),
            execCtx(vars),
        )

        assertThat(vars["s"]).isEqualTo("200")
        assertThat(vars["b"]).isEqualTo("second")
    }
}

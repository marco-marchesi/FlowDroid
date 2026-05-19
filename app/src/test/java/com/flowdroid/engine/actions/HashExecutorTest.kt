package com.flowdroid.engine.actions

import com.flowdroid.common.Outcome
import com.flowdroid.common.TimberStructuredLogger
import com.flowdroid.common.domain.NotificationEvent
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.ExecutionContext
import com.flowdroid.common.flow.ExecutionError
import com.flowdroid.common.flow.Flow
import com.flowdroid.common.flow.HashAlgorithm
import com.flowdroid.common.flow.Trigger
import com.flowdroid.common.flow.TriggerEvent
import com.flowdroid.engine.MagicTextEngineImpl
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.security.MessageDigest

class HashExecutorTest {

    private val executor = HashExecutor(MagicTextEngineImpl(), TimberStructuredLogger())

    private fun ctx(vars: MutableMap<String, String> = mutableMapOf()) = ExecutionContext(
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
                title = "Hello", text = null, bigText = null, subText = null, tickerText = null,
                channelId = null, groupKey = null,
                isOngoing = false, isClearable = true, isGroupSummary = false,
                importance = 3, notificationId = 1, actionLabels = emptyList(),
                rawExtrasJson = null,
            ),
            sbnKey = "k",
        ),
        variables = vars,
    )

    private fun reference(input: String, alg: String): String =
        MessageDigest.getInstance(alg).digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    @Test fun `sha256 of ciao matches openssl`() = runTest {
        val vars = mutableMapOf<String, String>()
        val r = executor.execute(
            Action.Hash(input = "ciao", algorithm = HashAlgorithm.SHA256, intoVar = "h"),
            ctx(vars),
        )
        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
        assertThat(vars["h"]).isEqualTo(reference("ciao", "SHA-256"))
    }

    @Test fun `md5 sha1 sha512 round-trip`() = runTest {
        listOf(
            HashAlgorithm.MD5 to "MD5",
            HashAlgorithm.SHA1 to "SHA-1",
            HashAlgorithm.SHA512 to "SHA-512",
        ).forEach { (alg, jdk) ->
            val vars = mutableMapOf<String, String>()
            val r = executor.execute(
                Action.Hash(input = "hello", algorithm = alg, intoVar = "h"),
                ctx(vars),
            )
            assertThat(r).isInstanceOf(Outcome.Ok::class.java)
            assertThat(vars["h"]).isEqualTo(reference("hello", jdk))
        }
    }

    @Test fun `magic text in input is expanded`() = runTest {
        val vars = mutableMapOf("payload" to "ciao")
        val r = executor.execute(
            Action.Hash(input = "{payload}", algorithm = HashAlgorithm.SHA256, intoVar = "h"),
            ctx(vars),
        )
        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
        assertThat(vars["h"]).isEqualTo(reference("ciao", "SHA-256"))
    }

    @Test fun `blank intoVar is rejected`() = runTest {
        val r = executor.execute(
            Action.Hash(input = "x", algorithm = HashAlgorithm.SHA256, intoVar = " "),
            ctx(),
        )
        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat((r as Outcome.Err).error).isInstanceOf(ExecutionError.InvalidParam::class.java)
    }
}

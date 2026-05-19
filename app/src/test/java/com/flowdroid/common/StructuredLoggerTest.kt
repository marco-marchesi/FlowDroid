package com.flowdroid.common

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import timber.log.Timber

class StructuredLoggerTest {

    private val captured = mutableListOf<CapturedLog>()
    private val captureTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            captured += CapturedLog(priority, tag, message, t)
        }
    }
    private val logger = TimberStructuredLogger()

    @BeforeEach fun setUp() {
        captured.clear()
        Timber.plant(captureTree)
    }

    @AfterEach fun tearDown() {
        Timber.uproot(captureTree)
    }

    @Test fun `info logs at INFO priority`() {
        logger.info("Tag", "hello")
        assertThat(captured).hasSize(1)
        assertThat(captured[0].priority).isEqualTo(android.util.Log.INFO)
        assertThat(captured[0].tag).isEqualTo("Tag")
        assertThat(captured[0].message).isEqualTo("hello")
    }

    @Test fun `warn carries throwable`() {
        val t = RuntimeException("oops")
        logger.warn("T", "fail", t, "operation" to "read")
        assertThat(captured).hasSize(1)
        assertThat(captured[0].throwable).isSameInstanceAs(t)
        assertThat(captured[0].message).contains("operation=read")
    }

    @Test fun `fields are appended to message`() {
        logger.info("T", "tick", "count" to 5, "ok" to true)
        assertThat(captured[0].message).isEqualTo("tick {count=5, ok=true}")
    }

    @Test fun `sensitive keys are redacted`() {
        logger.info("T", "request", "Authorization" to "Bearer abc.def", "user" to "alice")
        assertThat(captured[0].message).contains("Authorization=***")
        assertThat(captured[0].message).contains("user=alice")
    }

    @Test fun `sensitive key match is case-insensitive`() {
        logger.info("T", "x", "PASSWORD" to "p4ss")
        assertThat(captured[0].message).contains("PASSWORD=***")
    }

    @Test fun `error without throwable still logs`() {
        logger.error("T", "boom")
        assertThat(captured).hasSize(1)
        assertThat(captured[0].priority).isEqualTo(android.util.Log.ERROR)
        assertThat(captured[0].throwable).isNull()
    }

    @Test fun `no fields means no trailing braces`() {
        logger.info("T", "clean")
        assertThat(captured[0].message).isEqualTo("clean")
    }

    private data class CapturedLog(
        val priority: Int,
        val tag: String?,
        val message: String,
        val throwable: Throwable?,
    )
}

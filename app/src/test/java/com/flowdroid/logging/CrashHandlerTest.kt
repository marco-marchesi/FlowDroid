package com.flowdroid.logging

import com.flowdroid.common.Clock
import com.flowdroid.common.Outcome
import com.flowdroid.common.domain.HealthEvent
import com.flowdroid.common.domain.LogEntry
import com.flowdroid.common.repo.HealthRepository
import com.flowdroid.common.repo.LogRepository
import com.flowdroid.common.repo.PersistError
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.robolectric.annotation.Config

@Config(manifest = Config.NONE)
class CrashHandlerTest {

    private class StubClock(val now: Long = 1_700_000_000_000L) : Clock {
        override fun nowMillis(): Long = now
        override fun elapsedRealtime(): Long = 0L
    }

    /** Empty-by-default log repo with overridable behaviors. */
    private open class StubLogRepo : LogRepository {
        var snapshotCalledWith: Int? = null
        val inserts = mutableListOf<LogEntry>()
        var failOnInsert: Boolean = false
        var throwOnSnapshot: Boolean = false

        override suspend fun insert(entry: LogEntry): Outcome<Long, PersistError> {
            if (failOnInsert) return Outcome.Err(PersistError.WriteFailed("boom"))
            inserts.add(entry)
            return Outcome.Ok(1L)
        }

        override suspend fun insertAll(entries: List<LogEntry>): Outcome<Int, PersistError> =
            Outcome.Ok(entries.size)

        override fun observeRecent(limit: Int, minLevel: LogEntry.Level): Flow<List<LogEntry>> = emptyFlow()

        override suspend fun snapshotRecent(count: Int): Outcome<List<LogEntry>, PersistError> {
            snapshotCalledWith = count
            if (throwOnSnapshot) throw RuntimeException("simulated snapshot failure")
            return Outcome.Ok(emptyList())
        }

        override suspend fun pruneOlderThan(olderThanMillis: Long): Outcome<Int, PersistError> =
            Outcome.Ok(0)
    }

    private class StubHealthRepo : HealthRepository {
        val events = mutableListOf<HealthEvent>()
        var failOnInsert: Boolean = false

        override suspend fun insert(event: HealthEvent): Outcome<Long, PersistError> {
            if (failOnInsert) return Outcome.Err(PersistError.WriteFailed("boom"))
            events.add(event)
            return Outcome.Ok(1L)
        }

        override fun observeRecent(limit: Int): Flow<List<HealthEvent>> = emptyFlow()

        override suspend fun mostRecent(kind: HealthEvent.Kind): Outcome<HealthEvent?, PersistError> =
            Outcome.Ok(null)

        override suspend fun countSince(
            kinds: Set<HealthEvent.Kind>,
            sinceMillis: Long,
        ): Outcome<Int, PersistError> = Outcome.Ok(0)

        override suspend fun pruneOlderThan(olderThanMillis: Long): Outcome<Int, PersistError> =
            Outcome.Ok(0)
    }

    private var savedHandler: Thread.UncaughtExceptionHandler? = null

    @BeforeEach
    fun saveHandler() {
        savedHandler = Thread.getDefaultUncaughtExceptionHandler()
    }

    @AfterEach
    fun restoreHandler() {
        Thread.setDefaultUncaughtExceptionHandler(savedHandler)
    }

    @Test
    fun `install chains the previous handler`() {
        val previous = mockk<Thread.UncaughtExceptionHandler>(relaxed = true)
        Thread.setDefaultUncaughtExceptionHandler(previous)

        val handler = CrashHandler(StubLogRepo(), StubHealthRepo(), StubClock())
        handler.install()

        assertThat(Thread.getDefaultUncaughtExceptionHandler()).isNotSameInstanceAs(previous)

        // Triggering the new handler must delegate to the previous one.
        val thread = Thread.currentThread()
        val ex = RuntimeException("uh oh")
        handler.handleUncaught(thread, ex)
        verify { previous.uncaughtException(thread, ex) }
    }

    @Test
    fun `install is idempotent`() {
        val previous = mockk<Thread.UncaughtExceptionHandler>(relaxed = true)
        Thread.setDefaultUncaughtExceptionHandler(previous)

        val handler = CrashHandler(StubLogRepo(), StubHealthRepo(), StubClock())
        handler.install()
        val afterFirst = Thread.getDefaultUncaughtExceptionHandler()
        handler.install()
        val afterSecond = Thread.getDefaultUncaughtExceptionHandler()

        assertThat(afterSecond).isSameInstanceAs(afterFirst)
    }

    @Test
    fun `on uncaught snapshotRecent CRASH event and synthetic log are written and previous handler invoked`() {
        val logRepo = StubLogRepo()
        val healthRepo = StubHealthRepo()
        val previous = mockk<Thread.UncaughtExceptionHandler>(relaxed = true)
        Thread.setDefaultUncaughtExceptionHandler(previous)

        val handler = CrashHandler(logRepo, healthRepo, StubClock())
        handler.install()

        val thread = Thread.currentThread()
        val ex = IllegalStateException("kaboom")
        handler.handleUncaught(thread, ex)

        assertThat(logRepo.snapshotCalledWith).isEqualTo(CrashHandler.SNAPSHOT_COUNT)
        assertThat(healthRepo.events).hasSize(1)
        val event = healthRepo.events.single()
        assertThat(event.kind).isEqualTo(HealthEvent.Kind.CRASH)
        assertThat(event.message).contains("IllegalStateException")
        assertThat(event.message).contains("kaboom")

        assertThat(logRepo.inserts).hasSize(1)
        val entry = logRepo.inserts.single()
        assertThat(entry.level).isEqualTo(LogEntry.Level.ERROR)
        assertThat(entry.tag).isEqualTo("CrashHandler")
        assertThat(entry.throwableClass).isEqualTo("IllegalStateException")
        assertThat(entry.stackTraceFirstLines).isNotNull()

        verify { previous.uncaughtException(thread, ex) }
    }

    @Test
    fun `previous handler is invoked even when repository throws`() {
        val logRepo = StubLogRepo().apply { throwOnSnapshot = true }
        val healthRepo = StubHealthRepo()
        val previous = mockk<Thread.UncaughtExceptionHandler>(relaxed = true)
        Thread.setDefaultUncaughtExceptionHandler(previous)

        val handler = CrashHandler(logRepo, healthRepo, StubClock())
        handler.install()

        val thread = Thread.currentThread()
        val ex = RuntimeException("doom")

        // Must not propagate even though snapshot throws.
        handler.handleUncaught(thread, ex)

        verify { previous.uncaughtException(thread, ex) }
    }

    @Test
    fun `previous handler is invoked when health insert returns Err`() {
        val logRepo = StubLogRepo()
        val healthRepo = StubHealthRepo().apply { failOnInsert = true }
        val previous = mockk<Thread.UncaughtExceptionHandler>(relaxed = true)
        Thread.setDefaultUncaughtExceptionHandler(previous)

        val handler = CrashHandler(logRepo, healthRepo, StubClock())
        handler.install()

        val thread = Thread.currentThread()
        val ex = RuntimeException("doom")
        handler.handleUncaught(thread, ex)

        verify { previous.uncaughtException(thread, ex) }
    }

    @Test
    fun `previous handler is invoked when log insert returns Err`() {
        val logRepo = StubLogRepo().apply { failOnInsert = true }
        val healthRepo = StubHealthRepo()
        val previous = mockk<Thread.UncaughtExceptionHandler>(relaxed = true)
        Thread.setDefaultUncaughtExceptionHandler(previous)

        val handler = CrashHandler(logRepo, healthRepo, StubClock())
        handler.install()

        val thread = Thread.currentThread()
        val ex = RuntimeException("doom")
        handler.handleUncaught(thread, ex)

        verify { previous.uncaughtException(thread, ex) }
    }

    @Test
    fun `handler tolerates a null previous handler`() {
        Thread.setDefaultUncaughtExceptionHandler(null)

        val handler = CrashHandler(StubLogRepo(), StubHealthRepo(), StubClock())
        handler.install()

        // Just must not throw.
        handler.handleUncaught(Thread.currentThread(), RuntimeException("x"))
    }

    @Test
    fun `health event carries throwable class and message via mockk`() {
        val logRepo = mockk<LogRepository>(relaxed = true)
        coEvery { logRepo.snapshotRecent(any()) } returns Outcome.Ok(emptyList())
        coEvery { logRepo.insert(any()) } returns Outcome.Ok(1L)

        val healthRepo = mockk<HealthRepository>(relaxed = true)
        val captured = slot<HealthEvent>()
        coEvery { healthRepo.insert(capture(captured)) } returns Outcome.Ok(1L)

        val previous = mockk<Thread.UncaughtExceptionHandler>(relaxed = true)
        Thread.setDefaultUncaughtExceptionHandler(previous)
        val handler = CrashHandler(logRepo, healthRepo, StubClock())
        handler.install()

        handler.handleUncaught(Thread.currentThread(), IllegalArgumentException("bad arg"))

        coVerify { logRepo.snapshotRecent(CrashHandler.SNAPSHOT_COUNT) }
        assertThat(captured.isCaptured).isTrue()
        assertThat(captured.captured.kind).isEqualTo(HealthEvent.Kind.CRASH)
        assertThat(captured.captured.message).contains("IllegalArgumentException")
        assertThat(captured.captured.message).contains("bad arg")
    }
}

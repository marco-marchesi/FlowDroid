package com.flowdroid.logging

import android.util.Log
import com.flowdroid.common.Clock
import com.flowdroid.common.Outcome
import com.flowdroid.common.domain.LogEntry
import com.flowdroid.common.repo.LogRepository
import com.flowdroid.common.repo.PersistError
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.robolectric.annotation.Config

@Config(manifest = Config.NONE)
class RoomLogTreeTest {

    /** Minimal in-memory clock so we can advance elapsed-realtime alongside the test dispatcher. */
    private class TestClock : Clock {
        var elapsed: Long = 0L
        override fun nowMillis(): Long = 1_700_000_000_000L + elapsed
        override fun elapsedRealtime(): Long = elapsed
    }

    /** Fake repository capturing every batch. Optional failure injection for one specific call. */
    private class FakeLogRepository : LogRepository {
        val batches = mutableListOf<List<LogEntry>>()
        @Volatile var failNext: Boolean = false
        @Volatile var throwNext: Boolean = false

        override suspend fun insert(entry: LogEntry): Outcome<Long, PersistError> {
            batches.add(listOf(entry))
            return Outcome.Ok(1L)
        }

        override suspend fun insertAll(entries: List<LogEntry>): Outcome<Int, PersistError> {
            if (throwNext) {
                throwNext = false
                throw RuntimeException("simulated repo throw")
            }
            if (failNext) {
                failNext = false
                return Outcome.Err(PersistError.WriteFailed("simulated"))
            }
            batches.add(entries.toList())
            return Outcome.Ok(entries.size)
        }

        override fun observeRecent(limit: Int, minLevel: LogEntry.Level): Flow<List<LogEntry>> = emptyFlow()

        override suspend fun snapshotRecent(count: Int): Outcome<List<LogEntry>, PersistError> =
            Outcome.Ok(emptyList())

        override suspend fun pruneOlderThan(olderThanMillis: Long): Outcome<Int, PersistError> =
            Outcome.Ok(0)
    }

    private fun buildTree(
        repo: LogRepository,
        clock: Clock,
        scope: CoroutineScope,
        debug: Boolean = true,
    ): RoomLogTree = RoomLogTree(
        logRepository = repo,
        clock = clock,
        isDebugBuild = debug,
        consumerScope = scope,
    )

    @Test
    fun `info log results in insert within 500ms`() = runTest {
        val repo = FakeLogRepository()
        val clock = TestClock()
        val consumerScope = backgroundScope
        val tree = buildTree(repo, clock, consumerScope)

        tree.log(Log.INFO, "TestTag", "hello", null)

        // The consumer waits up to FLUSH_INTERVAL_MS for a batch — advance virtual time.
        clock.elapsed += RoomLogTree.FLUSH_INTERVAL_MS
        advanceTimeBy(500)
        advanceUntilIdle()

        assertThat(repo.batches).hasSize(1)
        val entry = repo.batches[0].single()
        assertThat(entry.level).isEqualTo(LogEntry.Level.INFO)
        assertThat(entry.tag).isEqualTo("TestTag")
        assertThat(entry.message).isEqualTo("hello")
    }

    @Test
    fun `200 logs in tight loop produce at most 3 batched inserts`() = runTest {
        val repo = FakeLogRepository()
        val clock = TestClock()
        val consumerScope = backgroundScope
        val tree = buildTree(repo, clock, consumerScope)

        repeat(200) { i -> tree.log(Log.INFO, "T", "msg$i", null) }

        // Give the consumer enough virtual time to drain everything: each batch waits at most
        // FLUSH_INTERVAL_MS once the first item arrives.
        repeat(5) {
            clock.elapsed += RoomLogTree.FLUSH_INTERVAL_MS
            advanceTimeBy(RoomLogTree.FLUSH_INTERVAL_MS + 10)
            advanceUntilIdle()
        }

        val total = repo.batches.sumOf { it.size }
        assertThat(total).isEqualTo(200)
        assertThat(repo.batches.size).isAtMost(3)
        // Each batch should be capped at BATCH_SIZE.
        assertThat(repo.batches.all { it.size <= RoomLogTree.BATCH_SIZE }).isTrue()
    }

    @Test
    fun `batch insert error does not crash tree and next log still attempts`() = runTest {
        val repo = FakeLogRepository()
        val clock = TestClock()
        val consumerScope = backgroundScope
        val tree = buildTree(repo, clock, consumerScope)

        repo.failNext = true
        tree.log(Log.INFO, "T", "first", null)

        // Drain first batch (which returns Err).
        clock.elapsed += RoomLogTree.FLUSH_INTERVAL_MS
        advanceTimeBy(RoomLogTree.FLUSH_INTERVAL_MS + 10)
        advanceUntilIdle()

        // The first batch was an Err — repo did not record it.
        assertThat(repo.batches).isEmpty()

        // The tree must still be alive.
        assertDoesNotThrow {
            tree.log(Log.INFO, "T", "second", null)
        }

        clock.elapsed += RoomLogTree.FLUSH_INTERVAL_MS
        advanceTimeBy(RoomLogTree.FLUSH_INTERVAL_MS + 10)
        advanceUntilIdle()

        // Second batch should have been recorded successfully.
        assertThat(repo.batches).hasSize(1)
        assertThat(repo.batches[0].single().message).isEqualTo("second")
    }

    @Test
    fun `repo throw is swallowed and consumer keeps running`() = runTest {
        val repo = FakeLogRepository()
        val clock = TestClock()
        val consumerScope = backgroundScope
        val tree = buildTree(repo, clock, consumerScope)

        repo.throwNext = true
        tree.log(Log.INFO, "T", "boom", null)
        clock.elapsed += RoomLogTree.FLUSH_INTERVAL_MS
        advanceTimeBy(RoomLogTree.FLUSH_INTERVAL_MS + 10)
        advanceUntilIdle()

        tree.log(Log.INFO, "T", "ok", null)
        clock.elapsed += RoomLogTree.FLUSH_INTERVAL_MS
        advanceTimeBy(RoomLogTree.FLUSH_INTERVAL_MS + 10)
        advanceUntilIdle()

        // The throwing batch is lost; the next one succeeds.
        assertThat(repo.batches).hasSize(1)
        assertThat(repo.batches[0].single().message).isEqualTo("ok")
    }

    @Test
    fun `Authorization=Bearer value is redacted`() = runTest {
        val repo = FakeLogRepository()
        val clock = TestClock()
        val consumerScope = backgroundScope
        val tree = buildTree(repo, clock, consumerScope)

        tree.log(Log.INFO, "Net", "Authorization=Bearer xyz happened", null)

        clock.elapsed += RoomLogTree.FLUSH_INTERVAL_MS
        advanceTimeBy(RoomLogTree.FLUSH_INTERVAL_MS + 10)
        advanceUntilIdle()

        assertThat(repo.batches).hasSize(1)
        val entry = repo.batches[0].single()
        // Best-effort: the key is preserved, the value is replaced. We accept either casing on
        // the key since the regex is case-insensitive but preserves the matched text.
        assertThat(entry.message).contains("Authorization=***")
        assertThat(entry.message).doesNotContain("Bearer xyz")
    }

    @Test
    fun `multiple sensitive keys redacted in one message`() = runTest {
        val repo = FakeLogRepository()
        val clock = TestClock()
        val consumerScope = backgroundScope
        val tree = buildTree(repo, clock, consumerScope)

        tree.log(Log.WARN, "Net", "token=abc password=hunter2 keep=this", null)

        clock.elapsed += RoomLogTree.FLUSH_INTERVAL_MS
        advanceTimeBy(RoomLogTree.FLUSH_INTERVAL_MS + 10)
        advanceUntilIdle()

        val msg = repo.batches.single().single().message
        assertThat(msg).contains("token=***")
        assertThat(msg).contains("password=***")
        assertThat(msg).contains("keep=this")
    }

    @Test
    fun `debug entries are dropped in release builds`() = runTest {
        val repo = FakeLogRepository()
        val clock = TestClock()
        val consumerScope = backgroundScope
        val tree = buildTree(repo, clock, consumerScope, debug = false)

        tree.log(Log.DEBUG, "T", "noisy", null)
        tree.log(Log.VERBOSE, "T", "noisy", null)
        tree.log(Log.INFO, "T", "kept", null)

        clock.elapsed += RoomLogTree.FLUSH_INTERVAL_MS
        advanceTimeBy(RoomLogTree.FLUSH_INTERVAL_MS + 10)
        advanceUntilIdle()

        assertThat(repo.batches).hasSize(1)
        assertThat(repo.batches[0]).hasSize(1)
        assertThat(repo.batches[0].single().level).isEqualTo(LogEntry.Level.INFO)
    }

    @Test
    fun `throwable is captured with simpleName, truncated message and 12 stack lines`() = runTest {
        val repo = FakeLogRepository()
        val clock = TestClock()
        val consumerScope = backgroundScope
        val tree = buildTree(repo, clock, consumerScope)

        val longMsg = "x".repeat(2_000)
        val t = IllegalStateException(longMsg)
        tree.log(Log.ERROR, "T", "boom", t)

        clock.elapsed += RoomLogTree.FLUSH_INTERVAL_MS
        advanceTimeBy(RoomLogTree.FLUSH_INTERVAL_MS + 10)
        advanceUntilIdle()

        val entry = repo.batches.single().single()
        assertThat(entry.throwableClass).isEqualTo("IllegalStateException")
        assertThat(entry.throwableMessage!!.length).isAtMost(RoomLogTree.MAX_THROWABLE_MESSAGE_CHARS)
        val lines = entry.stackTraceFirstLines!!.split('\n')
        assertThat(lines.size).isAtMost(RoomLogTree.STACK_LINES)
    }
}

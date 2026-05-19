package com.flowdroid.data

import app.cash.turbine.test
import com.flowdroid.common.Outcome
import com.flowdroid.common.domain.LogEntry
import com.flowdroid.data.repo.LogRepositoryImpl
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class LogRepositoryImplTest : BaseRoomTest() {

    private lateinit var repo: LogRepositoryImpl

    @Before
    fun setUpRepo() {
        repo = LogRepositoryImpl(
            dao = db.logEntryDao(),
            logger = logger,
            clock = clock,
        )
    }

    @Test
    fun `insertAll persists 50 entries in one batch`() = runTest {
        val batch = (1..50).map { i ->
            sampleEntry(
                timestamp = i.toLong(),
                level = if (i % 4 == 0) LogEntry.Level.ERROR else LogEntry.Level.INFO,
                tag = "T$i",
                message = "msg-$i",
            )
        }
        val result = repo.insertAll(batch)
        assertThat(result).isInstanceOf(Outcome.Ok::class.java)
        assertThat((result as Outcome.Ok).value).isEqualTo(50)
    }

    @Test
    fun `insertAll with empty list returns zero`() = runTest {
        val result = repo.insertAll(emptyList())
        assertThat((result as Outcome.Ok).value).isEqualTo(0)
    }

    @Test
    fun `observeRecent filters by minimum level`() = runTest {
        // 4 levels x N each
        val entries = buildList {
            add(sampleEntry(timestamp = 1, level = LogEntry.Level.DEBUG))
            add(sampleEntry(timestamp = 2, level = LogEntry.Level.INFO))
            add(sampleEntry(timestamp = 3, level = LogEntry.Level.WARN))
            add(sampleEntry(timestamp = 4, level = LogEntry.Level.ERROR))
            add(sampleEntry(timestamp = 5, level = LogEntry.Level.DEBUG))
            add(sampleEntry(timestamp = 6, level = LogEntry.Level.WARN))
        }
        repo.insertAll(entries)

        // minLevel = DEBUG → all 6
        repo.observeRecent(limit = 100, minLevel = LogEntry.Level.DEBUG).test {
            assertThat(awaitItem()).hasSize(6)
            cancelAndIgnoreRemainingEvents()
        }
        // minLevel = WARN → WARN + ERROR = 3
        repo.observeRecent(limit = 100, minLevel = LogEntry.Level.WARN).test {
            val rows = awaitItem()
            assertThat(rows).hasSize(3)
            assertThat(rows.map { it.level }).containsExactly(
                LogEntry.Level.WARN, LogEntry.Level.ERROR, LogEntry.Level.WARN,
            ).inOrder()
            cancelAndIgnoreRemainingEvents()
        }
        // minLevel = ERROR → only ERROR
        repo.observeRecent(limit = 100, minLevel = LogEntry.Level.ERROR).test {
            val rows = awaitItem()
            assertThat(rows).hasSize(1)
            assertThat(rows.first().level).isEqualTo(LogEntry.Level.ERROR)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeRecent emits newest-first ordering by timestamp`() = runTest {
        repo.insertAll(
            listOf(
                sampleEntry(timestamp = 100L, message = "older"),
                sampleEntry(timestamp = 200L, message = "newer"),
                sampleEntry(timestamp = 150L, message = "middle"),
            ),
        )
        repo.observeRecent(limit = 10).test {
            val rows = awaitItem()
            assertThat(rows.map { it.message }).containsExactly("newer", "middle", "older").inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `snapshotRecent returns most-recent entries one-shot`() = runTest {
        (1..20).forEach { i ->
            repo.insert(sampleEntry(timestamp = i.toLong(), message = "m-$i"))
        }
        val snapshot = repo.snapshotRecent(count = 5)
        assertThat(snapshot).isInstanceOf(Outcome.Ok::class.java)
        val rows = (snapshot as Outcome.Ok).value
        assertThat(rows).hasSize(5)
        assertThat(rows.map { it.message }).containsExactly("m-20", "m-19", "m-18", "m-17", "m-16")
            .inOrder()
    }

    @Test
    fun `pruneOlderThan deletes by timestamp`() = runTest {
        listOf(10L, 20L, 30L, 40L, 50L).forEach { t ->
            repo.insert(sampleEntry(timestamp = t))
        }
        val pruned = repo.pruneOlderThan(olderThanMillis = 35L)
        assertThat((pruned as Outcome.Ok).value).isEqualTo(3) // 10, 20, 30
    }

    private fun sampleEntry(
        timestamp: Long,
        level: LogEntry.Level = LogEntry.Level.INFO,
        tag: String = "tag",
        message: String = "msg",
    ): LogEntry = LogEntry(
        id = 0,
        timestampMillis = timestamp,
        level = level,
        tag = tag,
        message = message,
        fieldsJson = null,
        throwableClass = null,
        throwableMessage = null,
        stackTraceFirstLines = null,
    )
}

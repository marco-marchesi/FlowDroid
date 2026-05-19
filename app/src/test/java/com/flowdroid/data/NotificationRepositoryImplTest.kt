package com.flowdroid.data

import app.cash.turbine.test
import com.flowdroid.common.Outcome
import com.flowdroid.common.domain.NotificationEvent
import com.flowdroid.data.repo.NotificationRepositoryImpl
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class NotificationRepositoryImplTest : BaseRoomTest() {

    private lateinit var repo: NotificationRepositoryImpl

    @Before
    fun setUpRepo() {
        repo = NotificationRepositoryImpl(
            dao = db.notificationEventDao(),
            logger = logger,
            clock = clock,
        )
    }

    @Test
    fun `insert assigns rowid and returns Outcome Ok`() = runTest {
        val event = sampleEvent(postTime = 1_000L, sbnKey = "k-1", pkg = "com.example")
        val result = repo.insert(event)

        assertThat(result).isInstanceOf(Outcome.Ok::class.java)
        val id = (result as Outcome.Ok).value
        assertThat(id).isGreaterThan(0L)
    }

    @Test
    fun `observeRecent emits newest first across 100 inserts`() = runTest {
        // Insert 100 events with strictly increasing postTime to guarantee deterministic order.
        repeat(100) { i ->
            val r = repo.insert(sampleEvent(postTime = (i + 1).toLong(), sbnKey = "k-$i"))
            assertThat(r).isInstanceOf(Outcome.Ok::class.java)
        }

        repo.observeRecent(limit = 100).test {
            val first = awaitItem()
            assertThat(first).hasSize(100)
            // postTime descending: first row = 100, second = 99, ...
            assertThat(first.first().postTimeMillis).isEqualTo(100L)
            assertThat(first.last().postTimeMillis).isEqualTo(1L)
            // Strictly decreasing.
            val times = first.map { it.postTimeMillis }
            assertThat(times).isInOrder(Comparator.reverseOrder<Long>())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeRecent respects limit`() = runTest {
        repeat(10) { i ->
            repo.insert(sampleEvent(postTime = (i + 1).toLong(), sbnKey = "k-$i"))
        }
        repo.observeRecent(limit = 5).test {
            val items = awaitItem()
            assertThat(items).hasSize(5)
            assertThat(items.first().postTimeMillis).isEqualTo(10L)
            assertThat(items.last().postTimeMillis).isEqualTo(6L)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `countSince returns events at or after threshold`() = runTest {
        // 5 events with times 10, 20, 30, 40, 50.
        listOf(10L, 20L, 30L, 40L, 50L).forEachIndexed { i, t ->
            repo.insert(sampleEvent(postTime = t, sbnKey = "k-$i"))
        }

        val countAll = repo.countSince(sinceMillis = 0L)
        assertThat(countAll).isInstanceOf(Outcome.Ok::class.java)
        assertThat((countAll as Outcome.Ok).value).isEqualTo(5)

        val countFrom30 = repo.countSince(sinceMillis = 30L)
        assertThat((countFrom30 as Outcome.Ok).value).isEqualTo(3)

        val countFromFuture = repo.countSince(sinceMillis = 1_000L)
        assertThat((countFromFuture as Outcome.Ok).value).isEqualTo(0)
    }

    @Test
    fun `pruneOlderThan removes rows strictly older and returns affected count`() = runTest {
        listOf(10L, 20L, 30L, 40L, 50L).forEachIndexed { i, t ->
            repo.insert(sampleEvent(postTime = t, sbnKey = "k-$i"))
        }

        val pruned = repo.pruneOlderThan(olderThanMillis = 30L)
        assertThat((pruned as Outcome.Ok).value).isEqualTo(2) // 10 and 20

        val remaining = repo.countSince(0L)
        assertThat((remaining as Outcome.Ok).value).isEqualTo(3)
    }

    @Test
    fun `actionLabels roundtrip including empty list`() = runTest {
        val empty = sampleEvent(postTime = 1L, sbnKey = "empty", actionLabels = emptyList())
        val full = sampleEvent(
            postTime = 2L,
            sbnKey = "full",
            actionLabels = listOf("Reply", "Mark as read", "Snooze"),
        )
        repo.insert(empty)
        repo.insert(full)

        repo.observeRecent(limit = 10).test {
            val rows = awaitItem()
            val byKey = rows.associateBy { it.sbnKey }
            assertThat(byKey["empty"]!!.actionLabels).isEmpty()
            assertThat(byKey["full"]!!.actionLabels).containsExactly("Reply", "Mark as read", "Snooze")
                .inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `flow emits when new events are inserted`() = runTest {
        repo.observeRecent(limit = 50).test {
            assertThat(awaitItem()).isEmpty()

            repo.insert(sampleEvent(postTime = 10L, sbnKey = "first"))
            val afterFirst = awaitItem()
            assertThat(afterFirst).hasSize(1)
            assertThat(afterFirst.first().sbnKey).isEqualTo("first")

            repo.insert(sampleEvent(postTime = 20L, sbnKey = "second"))
            val afterSecond = awaitItem()
            assertThat(afterSecond).hasSize(2)
            assertThat(afterSecond.first().sbnKey).isEqualTo("second")
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun sampleEvent(
        postTime: Long,
        sbnKey: String,
        pkg: String = "com.example.app",
        actionLabels: List<String> = emptyList(),
    ): NotificationEvent = NotificationEvent(
        id = 0,
        sbnKey = sbnKey,
        packageName = pkg,
        postTimeMillis = postTime,
        notificationPostTimeMillis = postTime,
        title = "title-$sbnKey",
        text = "text-$sbnKey",
        bigText = null,
        subText = null,
        tickerText = null,
        channelId = "channel-default",
        groupKey = null,
        isOngoing = false,
        isClearable = true,
        isGroupSummary = false,
        importance = 3,
        notificationId = 42,
        actionLabels = actionLabels,
        rawExtrasJson = null,
    )
}

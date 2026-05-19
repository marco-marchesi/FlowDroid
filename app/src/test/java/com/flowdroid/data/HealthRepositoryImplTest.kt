package com.flowdroid.data

import com.flowdroid.common.Outcome
import com.flowdroid.common.domain.HealthEvent
import com.flowdroid.data.repo.HealthRepositoryImpl
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class HealthRepositoryImplTest : BaseRoomTest() {

    private lateinit var repo: HealthRepositoryImpl

    @Before
    fun setUpRepo() {
        repo = HealthRepositoryImpl(
            dao = db.healthEventDao(),
            logger = logger,
            clock = clock,
        )
    }

    @Test
    fun `insert assigns rowid`() = runTest {
        val r = repo.insert(sampleEvent(timestamp = 1L, kind = HealthEvent.Kind.LISTENER_CONNECTED))
        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
        assertThat((r as Outcome.Ok).value).isGreaterThan(0L)
    }

    @Test
    fun `mostRecent returns latest of each kind independently`() = runTest {
        // Two LISTENER_CONNECTED, three WATCHDOG_TICK.
        repo.insert(sampleEvent(1L, HealthEvent.Kind.LISTENER_CONNECTED, message = "lc-1"))
        repo.insert(sampleEvent(2L, HealthEvent.Kind.WATCHDOG_TICK, message = "wt-1"))
        repo.insert(sampleEvent(3L, HealthEvent.Kind.LISTENER_CONNECTED, message = "lc-2"))
        repo.insert(sampleEvent(4L, HealthEvent.Kind.WATCHDOG_TICK, message = "wt-2"))
        repo.insert(sampleEvent(5L, HealthEvent.Kind.WATCHDOG_TICK, message = "wt-3"))

        val lc = repo.mostRecent(HealthEvent.Kind.LISTENER_CONNECTED)
        assertThat(lc).isInstanceOf(Outcome.Ok::class.java)
        assertThat((lc as Outcome.Ok).value?.message).isEqualTo("lc-2")
        assertThat(lc.value?.timestampMillis).isEqualTo(3L)

        val wt = repo.mostRecent(HealthEvent.Kind.WATCHDOG_TICK)
        assertThat((wt as Outcome.Ok).value?.message).isEqualTo("wt-3")
        assertThat(wt.value?.timestampMillis).isEqualTo(5L)
    }

    @Test
    fun `mostRecent returns null when no event of that kind`() = runTest {
        repo.insert(sampleEvent(1L, HealthEvent.Kind.WATCHDOG_TICK))
        val r = repo.mostRecent(HealthEvent.Kind.CRASH)
        assertThat((r as Outcome.Ok).value).isNull()
    }

    @Test
    fun `countSince counts events of given kinds in window`() = runTest {
        // Time series across multiple kinds.
        repo.insert(sampleEvent(10L, HealthEvent.Kind.LISTENER_CONNECTED))
        repo.insert(sampleEvent(20L, HealthEvent.Kind.LISTENER_DISCONNECTED))
        repo.insert(sampleEvent(30L, HealthEvent.Kind.LISTENER_REBIND_REQUESTED))
        repo.insert(sampleEvent(40L, HealthEvent.Kind.LISTENER_CONNECTED))
        repo.insert(sampleEvent(50L, HealthEvent.Kind.WATCHDOG_TICK))

        val listenerKinds = setOf(
            HealthEvent.Kind.LISTENER_CONNECTED,
            HealthEvent.Kind.LISTENER_DISCONNECTED,
            HealthEvent.Kind.LISTENER_REBIND_REQUESTED,
        )

        val allListener = repo.countSince(listenerKinds, sinceMillis = 0L)
        assertThat((allListener as Outcome.Ok).value).isEqualTo(4)

        val recentListener = repo.countSince(listenerKinds, sinceMillis = 25L)
        assertThat((recentListener as Outcome.Ok).value).isEqualTo(2) // events at 30 and 40

        val watchdogOnly = repo.countSince(setOf(HealthEvent.Kind.WATCHDOG_TICK), sinceMillis = 0L)
        assertThat((watchdogOnly as Outcome.Ok).value).isEqualTo(1)
    }

    @Test
    fun `countSince with empty kinds returns zero without hitting database`() = runTest {
        repo.insert(sampleEvent(1L, HealthEvent.Kind.WATCHDOG_TICK))
        val r = repo.countSince(emptySet(), sinceMillis = 0L)
        assertThat((r as Outcome.Ok).value).isEqualTo(0)
    }

    @Test
    fun `pruneOlderThan removes rows strictly older`() = runTest {
        listOf(10L, 20L, 30L, 40L).forEach { t ->
            repo.insert(sampleEvent(t, HealthEvent.Kind.WATCHDOG_TICK))
        }
        val r = repo.pruneOlderThan(olderThanMillis = 25L)
        assertThat((r as Outcome.Ok).value).isEqualTo(2)
    }

    private fun sampleEvent(
        timestamp: Long,
        kind: HealthEvent.Kind,
        message: String = "msg",
        outcome: String? = null,
    ): HealthEvent = HealthEvent(
        id = 0,
        timestampMillis = timestamp,
        kind = kind,
        message = message,
        outcome = outcome,
    )
}

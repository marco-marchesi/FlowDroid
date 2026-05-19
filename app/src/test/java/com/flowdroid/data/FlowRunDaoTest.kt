package com.flowdroid.data

import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.flowdroid.data.db.FlowRunDao
import com.flowdroid.data.db.FlowRunEntity
import com.flowdroid.data.db.FlowDroidDatabase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class FlowRunDaoTest {

    private lateinit var db: FlowDroidDatabase
    private lateinit var dao: FlowRunDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            FlowDroidDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.flowRunDao()
    }

    @After
    fun tearDown() { db.close() }

    private fun row(
        flowId: String = "f1",
        execId: String = "ex-1",
        startedAt: Long = 1_000L,
        ok: Boolean = true,
        errCount: Int = 0,
    ) = FlowRunEntity(
        id = 0L,
        flowId = flowId,
        flowName = "Test",
        executionId = execId,
        triggerKind = "NotificationFired",
        startedAtMillis = startedAt,
        endedAtMillis = startedAt + 100L,
        ok = ok,
        okCount = if (ok) 2 else 1,
        errCount = errCount,
        aborted = !ok,
        errorMessage = if (ok) null else "boom",
        actionResultsJson = "[]",
    )

    @Test fun `insert then observeRecentForFlow returns newest-first within flow`() = runTest {
        dao.insert(row(flowId = "A", execId = "a1", startedAt = 100L))
        dao.insert(row(flowId = "A", execId = "a2", startedAt = 300L))
        dao.insert(row(flowId = "B", execId = "b1", startedAt = 200L))

        val onlyA = dao.observeRecentForFlow("A", limit = 10).first()
        assertThat(onlyA.map { it.executionId }).containsExactly("a2", "a1").inOrder()
    }

    @Test fun `observeRecentAll spans every flow newest-first`() = runTest {
        dao.insert(row(flowId = "A", execId = "a1", startedAt = 100L))
        dao.insert(row(flowId = "B", execId = "b1", startedAt = 200L))
        dao.insert(row(flowId = "A", execId = "a2", startedAt = 50L))

        val all = dao.observeRecentAll(limit = 10).first()
        assertThat(all.map { it.executionId }).containsExactly("b1", "a1", "a2").inOrder()
    }

    @Test fun `pruneOlderThan deletes only old rows`() = runTest {
        dao.insert(row(execId = "old", startedAt = 1L))
        dao.insert(row(execId = "fresh", startedAt = 9_999_999L))
        val deleted = dao.pruneOlderThan(1_000_000L)
        assertThat(deleted).isEqualTo(1)
        val remaining = dao.observeRecentAll(10).first()
        assertThat(remaining.map { it.executionId }).containsExactly("fresh")
    }
}

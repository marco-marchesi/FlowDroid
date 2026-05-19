package com.flowdroid.data

import app.cash.turbine.test
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.Flow as DomainFlow
import com.flowdroid.common.flow.Trigger
import com.flowdroid.data.db.FlowDao
import com.flowdroid.data.db.FlowEntity
import com.flowdroid.data.db.deserializeTriggers
import com.flowdroid.data.db.serializeActions
import com.flowdroid.data.db.serializeTriggers
import com.flowdroid.data.db.toEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class FlowDaoTest : BaseRoomTest() {

    private lateinit var dao: FlowDao

    @Before
    fun setUpDao() {
        dao = db.flowDao()
    }

    @Test
    fun `database opens at version 4`() {
        // openHelper.readableDatabase forces Room to open the underlying SupportSQLite, which is
        // when the schema validator runs. If we shipped a missing entity / bad migration, this
        // call would throw IllegalStateException("Migration didn't properly handle ...").
        val version = db.openHelper.readableDatabase.version
        assertThat(version).isEqualTo(4)
    }

    @Test
    fun `upsert then get returns same entity`() = runTest {
        val entity = sampleEntity(id = "flow-1", name = "first", enabled = true, updatedAt = 100L)
        dao.upsert(entity)
        val fetched = dao.get("flow-1")
        assertThat(fetched).isEqualTo(entity)
    }

    @Test
    fun `get returns null for unknown id`() = runTest {
        val fetched = dao.get("does-not-exist")
        assertThat(fetched).isNull()
    }

    @Test
    fun `observeAll emits ordered by updatedAt DESC`() = runTest {
        dao.upsert(sampleEntity(id = "a", updatedAt = 10L))
        dao.upsert(sampleEntity(id = "b", updatedAt = 30L))
        dao.upsert(sampleEntity(id = "c", updatedAt = 20L))

        dao.observeAll().test {
            val rows = awaitItem()
            assertThat(rows.map { it.id }).containsExactly("b", "c", "a").inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `enabled returns only enabled flows`() = runTest {
        dao.upsert(sampleEntity(id = "on-1", enabled = true, updatedAt = 1L))
        dao.upsert(sampleEntity(id = "off-1", enabled = false, updatedAt = 2L))
        dao.upsert(sampleEntity(id = "on-2", enabled = true, updatedAt = 3L))

        val enabled = dao.enabled()
        assertThat(enabled.map { it.id }).containsExactly("on-1", "on-2")
    }

    @Test
    fun `setEnabled updates row and bumps updatedAt`() = runTest {
        dao.upsert(sampleEntity(id = "flow-x", enabled = true, updatedAt = 100L))

        val affected = dao.setEnabled(id = "flow-x", enabled = false, now = 999L)
        assertThat(affected).isEqualTo(1)

        val fetched = dao.get("flow-x")!!
        assertThat(fetched.enabled).isFalse()
        assertThat(fetched.updatedAt).isEqualTo(999L)
    }

    @Test
    fun `setEnabled returns 0 for missing id`() = runTest {
        val affected = dao.setEnabled(id = "ghost", enabled = true, now = 1L)
        assertThat(affected).isEqualTo(0)
    }

    @Test
    fun `delete returns 1 for existing and 0 for missing`() = runTest {
        dao.upsert(sampleEntity(id = "flow-del"))

        val deletedExisting = dao.delete("flow-del")
        assertThat(deletedExisting).isEqualTo(1)

        val deletedMissing = dao.delete("flow-del")
        assertThat(deletedMissing).isEqualTo(0)
    }

    @Test
    fun `upsert with REPLACE behaviour overwrites existing row`() = runTest {
        dao.upsert(sampleEntity(id = "flow-r", name = "old", updatedAt = 1L))
        dao.upsert(sampleEntity(id = "flow-r", name = "new", updatedAt = 2L))

        val fetched = dao.get("flow-r")!!
        assertThat(fetched.name).isEqualTo("new")
        assertThat(fetched.updatedAt).isEqualTo(2L)
    }

    @Test
    fun `toEntity helper produces a roundtrippable entity`() = runTest {
        val domain = DomainFlow(
            id = "flow-helper",
            name = "helper",
            enabled = true,
            triggers = listOf(Trigger.NotificationPosted(packageName = "com.example")),
            actions = listOf(
                Action.PostNotification(title = "hi", text = "world"),
            ),
            notes = "some notes",
            createdAt = 1L,
            updatedAt = 2L,
        )
        dao.upsert(domain.toEntity())
        val fetched = dao.get("flow-helper")!!
        assertThat(fetched.name).isEqualTo("helper")
        assertThat(fetched.enabled).isTrue()
        assertThat(fetched.triggersJson).contains("NotificationPosted")
        assertThat(fetched.actionsJson).contains("PostNotification")
    }

    @Test
    fun `multi-trigger flow round-trips through the entity layer`() = runTest {
        val triggers = listOf(
            Trigger.NotificationPosted(packageName = "com.a", debounceMillis = 1000L),
            Trigger.NotificationPosted(packageName = "com.b", titleRegex = "^Hi", debounceMillis = 2500L),
        )
        val entity = FlowEntity(
            id = "multi",
            name = "multi-trigger",
            enabled = true,
            triggersJson = serializeTriggers(triggers),
            actionsJson = serializeActions(
                listOf(Action.PostNotification(title = "t", text = "x")),
            ),
            notes = null,
            createdAt = 0L,
            updatedAt = 1L,
        )
        dao.upsert(entity)

        val fetched = dao.get("multi")!!
        val decoded = deserializeTriggers(fetched.triggersJson)
        assertThat(decoded).containsExactlyElementsIn(triggers).inOrder()
    }

    private fun sampleEntity(
        id: String,
        name: String = "name-$id",
        enabled: Boolean = true,
        notes: String? = null,
        createdAt: Long = 0L,
        updatedAt: Long = 0L,
    ): FlowEntity = FlowEntity(
        id = id,
        name = name,
        enabled = enabled,
        triggersJson = serializeTriggers(
            listOf(Trigger.NotificationPosted(packageName = "com.x")),
        ),
        actionsJson = serializeActions(
            listOf(Action.PostNotification(title = "t", text = "x")),
        ),
        notes = notes,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

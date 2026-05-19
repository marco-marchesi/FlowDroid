package com.flowdroid.data

import app.cash.turbine.test
import com.flowdroid.common.Outcome
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.Flow as DomainFlow
import com.flowdroid.common.flow.Trigger
import com.flowdroid.data.db.FlowEntity
import com.flowdroid.data.db.serializeActions
import com.flowdroid.data.db.serializeTriggers
import com.flowdroid.data.repo.FlowRepositoryImpl
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class FlowRepositoryImplTest : BaseRoomTest() {

    private lateinit var repo: FlowRepositoryImpl

    @Before
    fun setUpRepo() {
        repo = FlowRepositoryImpl(
            dao = db.flowDao(),
            clock = clock,
            logger = logger,
        )
    }

    @Test
    fun `upsert then get returns the same domain flow`() = runTest {
        clock.setWall(500L)
        val flow = sampleFlow(id = "u1", name = "first")
        val upserted = repo.upsert(flow)
        assertThat(upserted).isInstanceOf(Outcome.Ok::class.java)

        val fetched = repo.get("u1")
        assertThat(fetched).isInstanceOf(Outcome.Ok::class.java)
        val value = (fetched as Outcome.Ok).value
        assertThat(value).isNotNull()
        assertThat(value!!.id).isEqualTo("u1")
        assertThat(value.name).isEqualTo("first")
    }

    @Test
    fun `get returns Ok null for missing id`() = runTest {
        val fetched = repo.get("ghost")
        assertThat(fetched).isInstanceOf(Outcome.Ok::class.java)
        assertThat((fetched as Outcome.Ok).value).isNull()
    }

    @Test
    fun `observeAll emits domain flows newest-updated first`() = runTest {
        clock.setWall(100L); repo.upsert(sampleFlow(id = "a"))
        clock.setWall(300L); repo.upsert(sampleFlow(id = "b"))
        clock.setWall(200L); repo.upsert(sampleFlow(id = "c"))

        repo.observeAll().test {
            val rows = awaitItem()
            assertThat(rows.map { it.id }).containsExactly("b", "c", "a").inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `delete removes the row and returns 1`() = runTest {
        clock.setWall(1L)
        repo.upsert(sampleFlow(id = "del"))
        val outcome = repo.delete("del")
        assertThat((outcome as Outcome.Ok).value).isEqualTo(1)

        val again = repo.delete("del")
        assertThat((again as Outcome.Ok).value).isEqualTo(0)
    }

    @Test
    fun `setEnabled toggles the flag and bumps updatedAt via clock`() = runTest {
        clock.setWall(100L)
        repo.upsert(sampleFlow(id = "s", enabled = true))

        clock.setWall(999L)
        val outcome = repo.setEnabled(id = "s", enabled = false)
        assertThat((outcome as Outcome.Ok).value).isEqualTo(1)

        val fetched = ((repo.get("s") as Outcome.Ok).value)!!
        assertThat(fetched.enabled).isFalse()
        assertThat(fetched.updatedAt).isEqualTo(999L)
    }

    @Test
    fun `enabledNotificationFlows returns only enabled NotificationPosted flows`() = runTest {
        clock.setWall(1L); repo.upsert(sampleFlow(id = "on-notif", enabled = true))
        clock.setWall(2L); repo.upsert(sampleFlow(id = "off-notif", enabled = false))
        clock.setWall(3L); repo.upsert(sampleFlow(id = "on-notif-2", enabled = true))

        val outcome = repo.enabledNotificationFlows()
        assertThat(outcome).isInstanceOf(Outcome.Ok::class.java)
        val flows = (outcome as Outcome.Ok).value
        assertThat(flows.map { it.id }).containsExactly("on-notif", "on-notif-2")
        flows.forEach {
            assertThat(it.triggers.any { t -> t is Trigger.NotificationPosted }).isTrue()
            assertThat(it.enabled).isTrue()
        }
    }

    @Test
    fun `enabledNotificationFlows returns flow with multiple NotificationPosted triggers`() = runTest {
        clock.setWall(1L)
        val flow = sampleFlow(
            id = "multi",
            enabled = true,
            triggers = listOf(
                Trigger.NotificationPosted(packageName = "com.a"),
                Trigger.NotificationPosted(packageName = "com.b"),
            ),
        )
        repo.upsert(flow)

        val outcome = repo.enabledNotificationFlows()
        assertThat(outcome).isInstanceOf(Outcome.Ok::class.java)
        val flows = (outcome as Outcome.Ok).value
        assertThat(flows.map { it.id }).containsExactly("multi")
        assertThat(flows.single().triggers).hasSize(2)
    }

    @Test
    fun `malformed JSON in db is skipped and others still returned`() = runTest {
        // Insert a valid flow.
        clock.setWall(1L)
        repo.upsert(sampleFlow(id = "good", enabled = true))

        // Bypass the repo to insert a row with intentionally malformed JSON.
        val badEntity = FlowEntity(
            id = "bad",
            name = "bad",
            enabled = true,
            triggersJson = "[{not-json", // malformed
            actionsJson = "[]",
            notes = null,
            createdAt = 1L,
            updatedAt = 2L,
        )
        db.flowDao().upsert(badEntity)

        val outcome = repo.enabledNotificationFlows()
        assertThat(outcome).isInstanceOf(Outcome.Ok::class.java)
        val flows = (outcome as Outcome.Ok).value
        assertThat(flows.map { it.id }).containsExactly("good")

        // observeAll also skips the bad one.
        repo.observeAll().test {
            val rows = awaitItem()
            assertThat(rows.map { it.id }).containsExactly("good")
            cancelAndIgnoreRemainingEvents()
        }

        // A WARN log was emitted for the decode failure.
        val warned = logger.warnings.any { it.message.contains("decode failed") }
        assertThat(warned).isTrue()
    }

    @Test
    fun `upsert sets createdAt on first insert and preserves it on update`() = runTest {
        clock.setWall(1_000L)
        repo.upsert(sampleFlow(id = "ts", name = "v1", createdAt = 0L))

        val first = ((repo.get("ts") as Outcome.Ok).value)!!
        assertThat(first.createdAt).isEqualTo(1_000L)
        assertThat(first.updatedAt).isEqualTo(1_000L)

        // Now update — clock advances, but the caller may re-supply the original createdAt.
        clock.setWall(2_500L)
        repo.upsert(first.copy(name = "v2"))

        val second = ((repo.get("ts") as Outcome.Ok).value)!!
        assertThat(second.createdAt).isEqualTo(1_000L) // preserved
        assertThat(second.updatedAt).isEqualTo(2_500L) // bumped
        assertThat(second.name).isEqualTo("v2")
    }

    @Test
    fun `upsert with non-zero createdAt does not overwrite it`() = runTest {
        clock.setWall(9_999L)
        // Caller passes an explicit createdAt; repo must not stomp it.
        repo.upsert(sampleFlow(id = "exp", createdAt = 42L))

        val fetched = ((repo.get("exp") as Outcome.Ok).value)!!
        assertThat(fetched.createdAt).isEqualTo(42L)
        assertThat(fetched.updatedAt).isEqualTo(9_999L)
    }

    @Test
    fun `upsert logs INFO with flowId on success`() = runTest {
        clock.setWall(1L)
        repo.upsert(sampleFlow(id = "logme"))
        val infoForUpsert = logger.entries.any {
            it.level == "INFO" && it.fields["op"] == "upsert" && it.fields["flowId"] == "logme"
        }
        assertThat(infoForUpsert).isTrue()
    }

    private fun sampleFlow(
        id: String,
        name: String = "name-$id",
        enabled: Boolean = true,
        createdAt: Long = 0L,
        updatedAt: Long = 0L,
        triggers: List<Trigger> = listOf(Trigger.NotificationPosted(packageName = "com.example")),
        actions: List<Action> = listOf(Action.PostNotification(title = "t", text = "x")),
    ): DomainFlow = DomainFlow(
        id = id,
        name = name,
        enabled = enabled,
        triggers = triggers,
        actions = actions,
        notes = null,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    @Suppress("unused")
    private fun rawEntity(
        id: String,
        enabled: Boolean = true,
        triggers: List<Trigger> = listOf(Trigger.NotificationPosted()),
        actions: List<Action> = emptyList(),
        updatedAt: Long = 0L,
    ): FlowEntity = FlowEntity(
        id = id,
        name = "raw-$id",
        enabled = enabled,
        triggersJson = serializeTriggers(triggers),
        actionsJson = serializeActions(actions),
        notes = null,
        createdAt = 0L,
        updatedAt = updatedAt,
    )
}

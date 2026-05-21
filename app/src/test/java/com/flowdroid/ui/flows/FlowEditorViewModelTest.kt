package com.flowdroid.ui.flows

import androidx.lifecycle.SavedStateHandle
import com.flowdroid.common.FakeClock
import com.flowdroid.common.Outcome
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.Flow
import com.flowdroid.common.flow.FlowRepository
import com.flowdroid.common.flow.Trigger
import com.flowdroid.data.settings.ConnectionSettingsRepository
import com.flowdroid.data.settings.MqttProfile
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FlowEditorViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repo: FlowRepository
    private lateinit var flowRunRepo: com.flowdroid.common.repo.FlowRunRepository
    private lateinit var connectionSettings: ConnectionSettingsRepository
    private val clock = FakeClock(wallMillis = 1_700_000_000_000L)

    @BeforeEach fun setup() {
        Dispatchers.setMain(dispatcher)
        repo = mockk(relaxed = false)
        // FlowEditorViewModel observes the per-flow run history for its status strip — return
        // an empty cold flow so the VM resolves but doesn't surface a label.
        flowRunRepo = mockk {
            io.mockk.every { observeRecentForFlow(any(), any()) } returns
                kotlinx.coroutines.flow.flowOf(emptyList())
        }
        connectionSettings = mockk {
            every { mqttProfile } returns kotlinx.coroutines.flow.flowOf(MqttProfile())
        }
    }

    @AfterEach fun tearDown() { Dispatchers.resetMain() }

    private fun newViewModel(flowId: String? = null): FlowEditorViewModel {
        val handle = SavedStateHandle(
            if (flowId == null) emptyMap() else mapOf("flowId" to flowId),
        )
        return FlowEditorViewModel(repo, flowRunRepo, clock, handle, connectionSettings)
    }

    @Test fun `new flow init has a sensible default draft`() = runTest(dispatcher) {
        val vm = newViewModel()
        advanceUntilIdle()
        val s = vm.state.value
        assertThat(s.draft.name).isEmpty()
        assertThat(s.draft.enabled).isTrue()
        assertThat(s.draft.trigger).isInstanceOf(Trigger.NotificationPosted::class.java)
        assertThat(s.draft.actions).hasSize(1)
        assertThat(s.draft.actions[0]).isInstanceOf(Action.ClickNotificationAction::class.java)
        assertThat(s.existingId).isNull()
        assertThat(s.isDirty).isFalse()
    }

    @Test fun `loading existing flow populates draft`() = runTest(dispatcher) {
        val existing = Flow(
            id = "abc",
            name = "Echo auto-join",
            enabled = false,
            triggers = listOf(Trigger.NotificationPosted(packageName = "com.example.app")),
            actions = listOf(Action.PostNotification(title = "T", text = "X")),
            createdAt = 10L,
            updatedAt = 20L,
        )
        coEvery { repo.get("abc") } returns Outcome.Ok(existing)

        val vm = newViewModel("abc")
        advanceUntilIdle()
        val s = vm.state.value
        assertThat(s.existingId).isEqualTo("abc")
        assertThat(s.draft.name).isEqualTo("Echo auto-join")
        assertThat(s.draft.enabled).isFalse()
        assertThat(s.draft.trigger.packageName).isEqualTo("com.example.app")
        assertThat(s.draft.actions).hasSize(1)
        assertThat(s.createdAt).isEqualTo(10L)
    }

    @Test fun `save with empty name returns Invalid and surfaces error`() = runTest(dispatcher) {
        val vm = newViewModel()
        advanceUntilIdle()
        val result = vm.save()
        assertThat(result).isInstanceOf(SaveResult.Invalid::class.java)
        assertThat((result as SaveResult.Invalid).errors.nameError).isNotNull()
        assertThat(vm.state.value.errors.nameError).isNotNull()
    }

    @Test fun `save with valid draft persists and returns Success`() = runTest(dispatcher) {
        val captured = slot<Flow>()
        coEvery { repo.upsert(capture(captured)) } returns Outcome.Ok(Unit)

        val vm = newViewModel()
        advanceUntilIdle()
        vm.setName("My flow")
        vm.updateActionAt(0, Action.ClickNotificationAction(labelRegex = "^Acknowledge$"))
        val result = vm.save()
        advanceUntilIdle()

        assertThat(result).isInstanceOf(SaveResult.Success::class.java)
        coVerify { repo.upsert(any()) }
        assertThat(captured.captured.name).isEqualTo("My flow")
        assertThat(captured.captured.id).isNotEmpty()
        assertThat(captured.captured.createdAt).isEqualTo(1_700_000_000_000L)
        assertThat(captured.captured.updatedAt).isEqualTo(1_700_000_000_000L)
        assertThat(vm.state.value.isDirty).isFalse()
    }

    @Test fun `save preserves id and createdAt for existing flow`() = runTest(dispatcher) {
        val existing = Flow(
            id = "abc",
            name = "Old",
            enabled = true,
            triggers = listOf(Trigger.NotificationPosted(packageName = "com.example.app")),
            actions = listOf(Action.ClickNotificationAction(labelRegex = "^Acknowledge$")),
            createdAt = 100L,
            updatedAt = 200L,
        )
        coEvery { repo.get("abc") } returns Outcome.Ok(existing)
        val captured = slot<Flow>()
        coEvery { repo.upsert(capture(captured)) } returns Outcome.Ok(Unit)

        val vm = newViewModel("abc")
        advanceUntilIdle()
        vm.setName("New name")
        clock.setWall(1_800_000_000_000L)
        vm.save()
        advanceUntilIdle()

        assertThat(captured.captured.id).isEqualTo("abc")
        assertThat(captured.captured.createdAt).isEqualTo(100L)
        assertThat(captured.captured.updatedAt).isEqualTo(1_800_000_000_000L)
        assertThat(captured.captured.name).isEqualTo("New name")
    }

    @Test fun `isDirty flips on setName and clears after successful save`() = runTest(dispatcher) {
        coEvery { repo.upsert(any()) } returns Outcome.Ok(Unit)
        val vm = newViewModel()
        advanceUntilIdle()
        assertThat(vm.state.value.isDirty).isFalse()
        vm.setName("Hello")
        assertThat(vm.state.value.isDirty).isTrue()
        vm.updateActionAt(0, Action.ClickNotificationAction(labelRegex = "X"))
        vm.save()
        advanceUntilIdle()
        assertThat(vm.state.value.isDirty).isFalse()
    }

    @Test fun `addAction appends and removeActionAt removes`() = runTest(dispatcher) {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.addAction(Action.LaunchApp(packageName = "com.foo"))
        assertThat(vm.state.value.draft.actions).hasSize(2)
        vm.removeActionAt(0)
        assertThat(vm.state.value.draft.actions).hasSize(1)
        assertThat(vm.state.value.draft.actions[0]).isInstanceOf(Action.LaunchApp::class.java)
    }

    @Test fun `validation flags missing action fields`() = runTest(dispatcher) {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.setName("Has name")
        // Default action has empty labelRegex.
        val result = vm.save()
        assertThat(result).isInstanceOf(SaveResult.Invalid::class.java)
        val errors = (result as SaveResult.Invalid).errors
        assertThat(errors.actionErrors).containsKey(0)
    }

    @Test fun `addTrigger appends a NotificationPosted to the draft`() = runTest(dispatcher) {
        val vm = newViewModel()
        advanceUntilIdle()
        assertThat(vm.state.value.draft.triggers).hasSize(1)
        vm.addTrigger()
        assertThat(vm.state.value.draft.triggers).hasSize(2)
        assertThat(vm.state.value.draft.triggers[1]).isInstanceOf(Trigger.NotificationPosted::class.java)
        assertThat(vm.state.value.isDirty).isTrue()
    }

    @Test fun `removeTriggerAt refuses when only one trigger remains`() = runTest(dispatcher) {
        val vm = newViewModel()
        advanceUntilIdle()
        assertThat(vm.state.value.draft.triggers).hasSize(1)
        vm.removeTriggerAt(0)
        // Last trigger must not be removable.
        assertThat(vm.state.value.draft.triggers).hasSize(1)
    }

    @Test fun `removeTriggerAt removes when more than one trigger`() = runTest(dispatcher) {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.addTrigger()
        vm.updateTriggerAt(1, Trigger.NotificationPosted(packageName = "com.bar"))
        vm.removeTriggerAt(0)
        assertThat(vm.state.value.draft.triggers).hasSize(1)
        assertThat((vm.state.value.draft.triggers[0] as Trigger.NotificationPosted).packageName)
            .isEqualTo("com.bar")
    }

    @Test fun `save with multiple triggers persists them all`() = runTest(dispatcher) {
        val captured = slot<Flow>()
        coEvery { repo.upsert(capture(captured)) } returns Outcome.Ok(Unit)

        val vm = newViewModel()
        advanceUntilIdle()
        vm.setName("Multi")
        vm.updateActionAt(0, Action.ClickNotificationAction(labelRegex = "x"))
        vm.updateTriggerAt(0, Trigger.NotificationPosted(packageName = "com.a"))
        vm.addTrigger()
        vm.updateTriggerAt(1, Trigger.NotificationPosted(packageName = "com.b"))
        val result = vm.save()
        advanceUntilIdle()

        assertThat(result).isInstanceOf(SaveResult.Success::class.java)
        assertThat(captured.captured.triggers).hasSize(2)
        assertThat((captured.captured.triggers[0] as Trigger.NotificationPosted).packageName).isEqualTo("com.a")
        assertThat((captured.captured.triggers[1] as Trigger.NotificationPosted).packageName).isEqualTo("com.b")
    }

    @Test fun `save returning PersistFailed surfaces saveError`() = runTest(dispatcher) {
        coEvery { repo.upsert(any()) } returns
            Outcome.Err(com.flowdroid.common.repo.PersistError.WriteFailed("disk full"))

        val vm = newViewModel()
        advanceUntilIdle()
        vm.setName("Valid")
        vm.updateActionAt(0, Action.ClickNotificationAction(labelRegex = "X"))
        val result = vm.save()
        advanceUntilIdle()
        assertThat(result).isInstanceOf(SaveResult.PersistFailed::class.java)
        assertThat(vm.state.value.saveError).isNotNull()
    }
}

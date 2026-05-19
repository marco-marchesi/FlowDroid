package com.flowdroid.ui.flows

import app.cash.turbine.test
import com.flowdroid.common.FakeClock
import com.flowdroid.common.Outcome
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.Flow
import com.flowdroid.common.flow.FlowRepository
import com.flowdroid.common.flow.Trigger
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FlowsListViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repo: FlowRepository
    private val clock = FakeClock(wallMillis = 1_700_000_000_000L)
    private lateinit var templates: com.flowdroid.data.flows.TemplateGallery

    @BeforeEach fun setup() {
        Dispatchers.setMain(dispatcher)
        repo = mockk()
        // The list view doesn't exercise templates in these tests; a relaxed mock with an empty
        // list is enough to satisfy the constructor signature.
        templates = mockk(relaxed = true) {
            every { this@mockk.templates } returns emptyList()
        }
    }

    @AfterEach fun tearDown() { Dispatchers.resetMain() }

    private fun sampleFlow(id: String, name: String, enabled: Boolean = true) = Flow(
        id = id,
        name = name,
        enabled = enabled,
        triggers = listOf(Trigger.NotificationPosted(packageName = "com.example.app", titleRegex = "^Echo.*")),
        actions = listOf(Action.ClickNotificationAction(labelRegex = "^Acknowledge$")),
        createdAt = 1L,
        updatedAt = 2L,
    )

    @Test fun `empty repository emits Empty state`() = runTest(dispatcher) {
        every { repo.observeAll() } returns MutableStateFlow(emptyList())
        val vm = FlowsListViewModel(repo, clock, templates)
        vm.state.test {
            assertThat(awaitItem()).isEqualTo(FlowsListUiState.Loading)
            assertThat(awaitItem()).isEqualTo(FlowsListUiState.Empty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `populated repository emits Ready with summaries in source order`() = runTest(dispatcher) {
        val flows = listOf(sampleFlow("a", "Alpha"), sampleFlow("b", "Beta", enabled = false))
        every { repo.observeAll() } returns MutableStateFlow(flows)

        val vm = FlowsListViewModel(repo, clock, templates)
        vm.state.test {
            // Skip Loading.
            awaitItem()
            val ready = awaitItem() as FlowsListUiState.Ready
            assertThat(ready.flows).hasSize(2)
            assertThat(ready.flows[0].id).isEqualTo("a")
            assertThat(ready.flows[0].name).isEqualTo("Alpha")
            assertThat(ready.flows[0].enabled).isTrue()
            assertThat(ready.flows[0].triggerSummary).contains("com.example.app")
            assertThat(ready.flows[0].actionCount).isEqualTo(1)
            assertThat(ready.flows[1].id).isEqualTo("b")
            assertThat(ready.flows[1].enabled).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `setEnabled forwards to repository`() = runTest(dispatcher) {
        every { repo.observeAll() } returns MutableStateFlow(emptyList())
        coEvery { repo.setEnabled(any(), any()) } returns Outcome.Ok(1)

        val vm = FlowsListViewModel(repo, clock, templates)
        vm.setEnabled("x", false)
        advanceUntilIdle()

        coVerify { repo.setEnabled("x", false) }
    }

    @Test fun `delete forwards to repository`() = runTest(dispatcher) {
        every { repo.observeAll() } returns MutableStateFlow(emptyList())
        coEvery { repo.delete(any()) } returns Outcome.Ok(1)

        val vm = FlowsListViewModel(repo, clock, templates)
        vm.delete("x")
        advanceUntilIdle()

        coVerify { repo.delete("x") }
    }

    @Test fun `setEnabled handles repository error without crashing`() = runTest(dispatcher) {
        every { repo.observeAll() } returns MutableStateFlow(emptyList())
        coEvery { repo.setEnabled(any(), any()) } returns
            Outcome.Err(com.flowdroid.common.repo.PersistError.WriteFailed("boom"))

        val vm = FlowsListViewModel(repo, clock, templates)
        vm.setEnabled("x", true)
        advanceUntilIdle()

        coVerify { repo.setEnabled("x", true) }
    }

    @Test fun `failing upstream emits Error state`() = runTest(dispatcher) {
        every { repo.observeAll() } returns flow { throw IllegalStateException("db down") }
        val vm = FlowsListViewModel(repo, clock, templates)
        vm.state.test {
            assertThat(awaitItem()).isEqualTo(FlowsListUiState.Loading)
            val err = awaitItem()
            assertThat(err).isInstanceOf(FlowsListUiState.Error::class.java)
            assertThat((err as FlowsListUiState.Error).message).contains("db down")
            cancelAndIgnoreRemainingEvents()
        }
    }
}

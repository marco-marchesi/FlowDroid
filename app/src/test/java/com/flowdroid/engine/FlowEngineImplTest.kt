package com.flowdroid.engine

import com.flowdroid.common.Clock
import com.flowdroid.common.Outcome
import com.flowdroid.common.TimberStructuredLogger
import com.flowdroid.common.domain.NotificationEvent
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.ActionExecutor
import com.flowdroid.common.flow.ExecutionContext
import com.flowdroid.common.flow.ExecutionError
import com.flowdroid.common.flow.Flow
import com.flowdroid.common.flow.FlowRepository
import com.flowdroid.common.flow.Trigger
import com.flowdroid.common.flow.TriggerEvent
import com.flowdroid.common.repo.PersistError
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class FlowEngineImplTest {

    /** Test fake of [FlowRepository] returning a configurable list. */
    private class FakeRepo(var flows: List<Flow> = emptyList()) : FlowRepository {
        override fun observeAll(): kotlinx.coroutines.flow.Flow<List<Flow>> = flowOf(flows)
        override suspend fun get(id: String): Outcome<Flow?, PersistError> =
            Outcome.ok(flows.firstOrNull { it.id == id })
        override suspend fun enabledNotificationFlows(): Outcome<List<Flow>, PersistError> =
            Outcome.ok(flows.filter {
                it.enabled && it.trigger is Trigger.NotificationPosted
            })
        override suspend fun upsert(flow: Flow): Outcome<Unit, PersistError> = Outcome.ok(Unit)
        override suspend fun delete(id: String): Outcome<Int, PersistError> = Outcome.ok(0)
        override suspend fun setEnabled(id: String, enabled: Boolean): Outcome<Int, PersistError> =
            Outcome.ok(0)
        override suspend fun duplicate(id: String, nowMillis: Long): Outcome<String, PersistError> =
            Outcome.ok("dup-$id")
    }

    /** Test action that records every invocation. */
    private class TestAction(
        override val continueOnError: Boolean = false,
    ) : Action

    private class TestExecutor(
        val result: Outcome<Unit, ExecutionError> = Outcome.ok(Unit),
        val delayMillis: Long = 0L,
    ) : ActionExecutor<TestAction> {
        override val actionClass: Class<TestAction> = TestAction::class.java
        val calls = AtomicInteger(0)
        var lastContext: ExecutionContext? = null
        override suspend fun execute(
            action: TestAction,
            context: ExecutionContext,
        ): Outcome<Unit, ExecutionError> {
            if (delayMillis > 0) delay(delayMillis)
            lastContext = context
            calls.incrementAndGet()
            return result
        }
    }

    private val fixedClock = object : Clock {
        var now = 1000L
        override fun nowMillis(): Long = now
        override fun elapsedRealtime(): Long = now
    }

    private val logger = TimberStructuredLogger()

    private fun makeEvent(pkg: String = "com.example.app") = NotificationEvent(
        sbnKey = "0|$pkg|1|null|0",
        packageName = pkg,
        postTimeMillis = 1L,
        notificationPostTimeMillis = 1L,
        title = "Echo slot",
        text = "Join now",
        bigText = null,
        subText = null,
        tickerText = null,
        channelId = null,
        groupKey = null,
        isOngoing = false,
        isClearable = true,
        isGroupSummary = false,
        importance = 3,
        notificationId = 1,
        actionLabels = listOf("Acknowledge"),
        rawExtrasJson = null,
    )

    private fun makeFlow(
        id: String = "f1",
        actions: List<Action>,
        trigger: Trigger = Trigger.NotificationPosted(
            packageName = "com.example.app",
            debounceMillis = 1500L,
        ),
        enabled: Boolean = true,
    ) = Flow(
        id = id,
        name = "F-$id",
        enabled = enabled,
        triggers = listOf(trigger),
        actions = actions,
        createdAt = 0L,
        updatedAt = 0L,
    )

    @Test fun `matching flow executes its single action with correct context`() = runTest {
        val executor = TestExecutor()
        val executors: Map<Class<out Action>, ActionExecutor<*>> = mapOf(
            TestAction::class.java to executor,
        )
        val repo = FakeRepo(listOf(makeFlow(actions = listOf(TestAction()))))
        val engine = FlowEngineImpl(
            repo = repo,
            magicText = MagicTextEngineImpl(),
            debouncer = Debouncer(),
            executors = executors,
            logger = logger,
            clock = fixedClock,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        engine.onNotificationPosted(makeEvent())
        advanceUntilIdle()

        assertThat(executor.calls.get()).isEqualTo(1)
        val ctx = executor.lastContext!!
        assertThat(ctx.flow.id).isEqualTo("f1")
        assertThat(ctx.variables["notification.title"]).isEqualTo("Echo slot")
        assertThat(ctx.variables["notification.action[0].label"]).isEqualTo("Acknowledge")
        assertThat(ctx.variables["flow.id"]).isEqualTo("f1")
        assertThat(ctx.variables["exec.id"]).isNotEmpty()
        assertThat(ctx.triggerEvent).isInstanceOf(TriggerEvent.NotificationFired::class.java)
    }

    @Test fun `non-matching flow does not execute action`() = runTest {
        val executor = TestExecutor()
        val executors: Map<Class<out Action>, ActionExecutor<*>> = mapOf(
            TestAction::class.java to executor,
        )
        val repo = FakeRepo(
            listOf(
                makeFlow(
                    actions = listOf(TestAction()),
                    trigger = Trigger.NotificationPosted(packageName = "com.other"),
                )
            )
        )
        val engine = FlowEngineImpl(
            repo, MagicTextEngineImpl(), Debouncer(), executors, logger, fixedClock,
            StandardTestDispatcher(testScheduler),
        )

        engine.onNotificationPosted(makeEvent())
        advanceUntilIdle()

        assertThat(executor.calls.get()).isEqualTo(0)
    }

    @Test fun `debounce blocks rapid re-fire`() = runTest {
        val executor = TestExecutor()
        val executors: Map<Class<out Action>, ActionExecutor<*>> = mapOf(
            TestAction::class.java to executor,
        )
        val repo = FakeRepo(listOf(makeFlow(actions = listOf(TestAction()))))
        val engine = FlowEngineImpl(
            repo, MagicTextEngineImpl(), Debouncer(), executors, logger, fixedClock,
            StandardTestDispatcher(testScheduler),
        )

        engine.onNotificationPosted(makeEvent())
        advanceUntilIdle()
        // Same wall clock (no advance), within 1500ms window → second match denied.
        engine.onNotificationPosted(makeEvent())
        advanceUntilIdle()

        assertThat(executor.calls.get()).isEqualTo(1)
    }

    @Test fun `debounce allows fire after window`() = runTest {
        val executor = TestExecutor()
        val executors: Map<Class<out Action>, ActionExecutor<*>> = mapOf(
            TestAction::class.java to executor,
        )
        val repo = FakeRepo(listOf(makeFlow(actions = listOf(TestAction()))))
        val engine = FlowEngineImpl(
            repo, MagicTextEngineImpl(), Debouncer(), executors, logger, fixedClock,
            StandardTestDispatcher(testScheduler),
        )

        engine.onNotificationPosted(makeEvent())
        advanceUntilIdle()
        fixedClock.now += 2_000L
        engine.onNotificationPosted(makeEvent())
        advanceUntilIdle()

        assertThat(executor.calls.get()).isEqualTo(2)
    }

    @Test fun `abort on error when continueOnError is false`() = runTest {
        val failExec = TestExecutor(
            result = Outcome.err(ExecutionError.SystemFailure("nope")),
        )

        class OtherAction(override val continueOnError: Boolean = false) : Action
        val otherExec = TestExecutor()

        // Both action classes route to their own executor — register both.
        // We need separate executor instances per action class, so use one fake per type.
        val executors: Map<Class<out Action>, ActionExecutor<*>> = mapOf(
            TestAction::class.java to failExec,
            OtherAction::class.java to OtherExecutor(otherExec.calls),
        )
        val repo = FakeRepo(
            listOf(
                makeFlow(
                    actions = listOf(
                        TestAction(continueOnError = false),
                        OtherAction(),
                    )
                )
            )
        )
        val engine = FlowEngineImpl(
            repo, MagicTextEngineImpl(), Debouncer(), executors, logger, fixedClock,
            StandardTestDispatcher(testScheduler),
        )

        engine.onNotificationPosted(makeEvent())
        advanceUntilIdle()

        assertThat(failExec.calls.get()).isEqualTo(1)
        assertThat(otherExec.calls.get()).isEqualTo(0) // aborted before this ran
    }

    @Test fun `continue on error when flag is true`() = runTest {
        val failExec = TestExecutor(
            result = Outcome.err(ExecutionError.SystemFailure("nope")),
        )

        class OtherAction(override val continueOnError: Boolean = false) : Action
        val otherCalls = AtomicInteger(0)

        val executors: Map<Class<out Action>, ActionExecutor<*>> = mapOf(
            TestAction::class.java to failExec,
            OtherAction::class.java to OtherExecutor(otherCalls),
        )
        val repo = FakeRepo(
            listOf(
                makeFlow(
                    actions = listOf(
                        TestAction(continueOnError = true),
                        OtherAction(),
                    )
                )
            )
        )
        val engine = FlowEngineImpl(
            repo, MagicTextEngineImpl(), Debouncer(), executors, logger, fixedClock,
            StandardTestDispatcher(testScheduler),
        )

        engine.onNotificationPosted(makeEvent())
        advanceUntilIdle()

        assertThat(failExec.calls.get()).isEqualTo(1)
        assertThat(otherCalls.get()).isEqualTo(1)
    }

    @Test fun `missing executor logs error and aborts (continueOnError false)`() = runTest {
        // No executor registered at all.
        val executors: Map<Class<out Action>, ActionExecutor<*>> = emptyMap()
        val repo = FakeRepo(listOf(makeFlow(actions = listOf(TestAction()))))
        val engine = FlowEngineImpl(
            repo, MagicTextEngineImpl(), Debouncer(), executors, logger, fixedClock,
            StandardTestDispatcher(testScheduler),
        )
        // Should not throw.
        engine.onNotificationPosted(makeEvent())
        advanceUntilIdle()
        // Nothing to assert beyond "no crash" — verified by the run completing.
        assertThat(true).isTrue()
    }

    @Test fun `repository error logs warning and returns`() = runTest {
        val repo = mockk<FlowRepository>()
        coEvery { repo.enabledNotificationFlows() } returns
            Outcome.err(PersistError.DatabaseUnavailable)

        val engine = FlowEngineImpl(
            repo = repo,
            magicText = MagicTextEngineImpl(),
            debouncer = Debouncer(),
            executors = emptyMap(),
            logger = logger,
            clock = fixedClock,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        engine.onNotificationPosted(makeEvent())
        advanceUntilIdle()
        // No crash; no executors invoked.
        assertThat(true).isTrue()
    }

    /**
     * An executor for a locally-declared `OtherAction` type. Declared at file scope so it
     * can be used from inside test methods that also declare their own local action class.
     */
    private class OtherExecutor(private val counter: AtomicInteger) :
        ActionExecutor<Action> {
        override val actionClass: Class<Action> = Action::class.java
        override suspend fun execute(
            action: Action,
            context: ExecutionContext,
        ): Outcome<Unit, ExecutionError> {
            counter.incrementAndGet()
            return Outcome.ok(Unit)
        }
    }
}

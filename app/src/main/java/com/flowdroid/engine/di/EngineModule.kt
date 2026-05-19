package com.flowdroid.engine.di

import com.flowdroid.common.flow.AccessibilityController
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.ActionExecutor
import com.flowdroid.common.flow.ActionRunner
import com.flowdroid.common.flow.FlowEngine
import com.flowdroid.common.flow.MagicTextEngine
import com.flowdroid.common.flow.SbnCache
import com.flowdroid.engine.FlowEngineImpl
import com.flowdroid.engine.MagicTextEngineImpl
import com.flowdroid.engine.SbnCacheImpl
import com.flowdroid.engine.accessibility.AccessibilityControllerImpl
import com.flowdroid.engine.actions.Base64Executor
import com.flowdroid.engine.actions.ClickNotificationActionExecutor
import com.flowdroid.engine.actions.DelayExecutor
import com.flowdroid.engine.actions.HashExecutor
import com.flowdroid.engine.actions.HttpActionExecutor
import com.flowdroid.engine.actions.IfExecutor
import com.flowdroid.engine.actions.LoopExecutor
import com.flowdroid.engine.actions.TryCatchExecutor
import com.flowdroid.engine.actions.UnlockScreenExecutor
import com.flowdroid.engine.actions.KillAppExecutor
import com.flowdroid.engine.actions.LaunchAppExecutor
import com.flowdroid.engine.actions.PostNotificationExecutor
import com.flowdroid.engine.actions.ReadFileExecutor
import com.flowdroid.engine.actions.SetVariableExecutor
import com.flowdroid.engine.actions.UiClickExecutor
import com.flowdroid.engine.actions.UiPressKeyExecutor
import com.flowdroid.engine.actions.UiSwipeExecutor
import com.flowdroid.engine.actions.UiTypeTextExecutor
import com.flowdroid.engine.actions.WriteFileExecutor
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoMap
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for the engine subsystem.
 *
 * Wires:
 *  - The interface→impl bindings for [MagicTextEngine], [SbnCache], and [FlowEngine].
 *  - One [ActionExecutor] per concrete [Action] subtype, into a single Hilt-provided map keyed
 *    on the action subtype's `Class`. The engine injects that map as
 *    `Map<Class<out Action>, @JvmSuppressWildcards ActionExecutor<*>>`.
 *
 * Note on variance: each `@Binds` method returns `ActionExecutor<*>` (wildcard) — concrete
 * executor types like `ActionExecutor<Action.ClickNotificationAction>` are upcast at the
 * binding site. The engine casts back to `ActionExecutor<Action>` at the call site using
 * `@Suppress("UNCHECKED_CAST")`; this is sound because the map key guarantees the action
 * runtime type matches.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class EngineModule {

    @Binds @Singleton
    abstract fun bindMagicTextEngine(impl: MagicTextEngineImpl): MagicTextEngine

    @Binds @Singleton
    abstract fun bindSbnCache(impl: SbnCacheImpl): SbnCache

    @Binds @Singleton
    abstract fun bindFlowEngine(impl: FlowEngineImpl): FlowEngine

    /**
     * Same singleton instance is exposed as [ActionRunner] so control-flow executors (If, future
     * Loop / TryCatch) can recurse into sub-flows without depending on the engine's concrete class.
     * Bound to the SAME instance via [Singleton]; [dagger.Lazy] in [IfExecutor] breaks the
     * construction-time cycle.
     */
    @Binds @Singleton
    abstract fun bindActionRunner(impl: FlowEngineImpl): ActionRunner

    @Binds @Singleton
    abstract fun bindAccessibilityController(impl: AccessibilityControllerImpl): AccessibilityController

    @Binds @IntoMap
    @ActionKey(Action.ClickNotificationAction::class)
    abstract fun bindClickNotificationAction(
        impl: ClickNotificationActionExecutor,
    ): ActionExecutor<*>

    @Binds @IntoMap
    @ActionKey(Action.LaunchApp::class)
    abstract fun bindLaunchApp(
        impl: LaunchAppExecutor,
    ): ActionExecutor<*>

    @Binds @IntoMap
    @ActionKey(Action.PostNotification::class)
    abstract fun bindPostNotification(
        impl: PostNotificationExecutor,
    ): ActionExecutor<*>

    @Binds @IntoMap
    @ActionKey(Action.Delay::class)
    abstract fun bindDelay(
        impl: DelayExecutor,
    ): ActionExecutor<*>

    @Binds @IntoMap
    @ActionKey(Action.KillApp::class)
    abstract fun bindKillApp(
        impl: KillAppExecutor,
    ): ActionExecutor<*>

    @Binds @IntoMap
    @ActionKey(Action.UiClick::class)
    abstract fun bindUiClick(
        impl: UiClickExecutor,
    ): ActionExecutor<*>

    @Binds @IntoMap
    @ActionKey(Action.UiSwipe::class)
    abstract fun bindUiSwipe(
        impl: UiSwipeExecutor,
    ): ActionExecutor<*>

    @Binds @IntoMap
    @ActionKey(Action.UiTypeText::class)
    abstract fun bindUiTypeText(
        impl: UiTypeTextExecutor,
    ): ActionExecutor<*>

    @Binds @IntoMap
    @ActionKey(Action.UiPressKey::class)
    abstract fun bindUiPressKey(
        impl: UiPressKeyExecutor,
    ): ActionExecutor<*>

    @Binds @IntoMap
    @ActionKey(Action.Http::class)
    abstract fun bindHttp(
        impl: HttpActionExecutor,
    ): ActionExecutor<*>

    @Binds @IntoMap
    @ActionKey(Action.SetVariable::class)
    abstract fun bindSetVariable(
        impl: SetVariableExecutor,
    ): ActionExecutor<*>

    @Binds @IntoMap
    @ActionKey(Action.ReadFile::class)
    abstract fun bindReadFile(
        impl: ReadFileExecutor,
    ): ActionExecutor<*>

    @Binds @IntoMap
    @ActionKey(Action.WriteFile::class)
    abstract fun bindWriteFile(
        impl: WriteFileExecutor,
    ): ActionExecutor<*>

    @Binds @IntoMap
    @ActionKey(Action.Base64::class)
    abstract fun bindBase64(
        impl: Base64Executor,
    ): ActionExecutor<*>

    @Binds @IntoMap
    @ActionKey(Action.Hash::class)
    abstract fun bindHash(
        impl: HashExecutor,
    ): ActionExecutor<*>

    @Binds @IntoMap
    @ActionKey(Action.If::class)
    abstract fun bindIf(
        impl: IfExecutor,
    ): ActionExecutor<*>

    @Binds @IntoMap
    @ActionKey(Action.Loop::class)
    abstract fun bindLoop(
        impl: LoopExecutor,
    ): ActionExecutor<*>

    @Binds @IntoMap
    @ActionKey(Action.TryCatch::class)
    abstract fun bindTryCatch(
        impl: TryCatchExecutor,
    ): ActionExecutor<*>

    @Binds @IntoMap
    @ActionKey(Action.UnlockScreen::class)
    abstract fun bindUnlockScreen(
        impl: UnlockScreenExecutor,
    ): ActionExecutor<*>
}

package com.flowdroid.engine.di

import com.flowdroid.common.flow.Action
import dagger.MapKey
import kotlin.reflect.KClass

/**
 * Multibinding map-key annotation: maps an [com.flowdroid.common.flow.ActionExecutor]
 * into a Hilt-provided map keyed on the action subtype's `Class`.
 *
 * Why `Class<out Action>` as the unwrap target:
 *  - The engine looks executors up via `action::class.java` (a [Class]). Using `Class` rather
 *    than `KClass` avoids any KClass↔Class conversion at the hot path.
 *  - Dagger / Hilt unwraps a `KClass<...>` annotation value into a runtime `Class<...>` for
 *    the generated map when the injection site asks for `Map<Class<...>, ...>`. This works
 *    out-of-the-box on Dagger 2.40+ (Hilt 2.40+).
 *
 * Usage:
 * ```
 * @Binds @IntoMap @ActionKey(Action.ClickNotificationAction::class)
 * abstract fun bindClick(impl: ClickNotificationActionExecutor): ActionExecutor<out Action>
 * ```
 */
@MapKey
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class ActionKey(val value: KClass<out Action>)

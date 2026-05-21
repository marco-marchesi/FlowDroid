package com.flowdroid.common.flow

import com.flowdroid.common.Outcome
import com.flowdroid.common.domain.NotificationEvent

/**
 * The execution engine — matches incoming events against flows, then dispatches actions.
 *
 * Implementations MUST:
 *  - be thread-safe (events arrive from the listener's coroutine scope)
 *  - never block the caller; matching is fast, action dispatch is async
 *  - log every match decision (even non-matches at DEBUG) so the user can debug why a flow
 *    didn't fire — the #1 support question is "I made a flow but it doesn't run"
 *
 * Phase 1 MVP scope: notification triggers only. Time/webhook/etc. triggers are wired via
 * separate entry points (e.g. an AlarmManager receiver) but ultimately call into the same
 * matcher/executor machinery.
 */
interface FlowEngine {

    /**
     * Called by [com.flowdroid.service.FlowDroidNotificationListenerService] for every
     * notification posted. Looks up enabled flows whose trigger matches and schedules them.
     *
     * MUST return quickly — actual action dispatch happens on the engine's own scope.
     */
    suspend fun onNotificationPosted(event: NotificationEvent)

    /**
     * Called by external trigger sources (time scheduler, webhook server) to fire a specific
     * flow directly. Bypasses matching — the caller has already proven the flow should fire.
     *
     * Per-flow debouncing still applies. Variables are populated based on the event subtype.
     */
    suspend fun onTriggered(flowId: String, event: TriggerEvent)
}

/**
 * Action executor — runs a single [Action] in the context of an [ExecutionContext].
 *
 * One executor per action kind; bound by Hilt multibinds (`@IntoMap` keyed on action class).
 */
interface ActionExecutor<A : Action> {

    /** The concrete subtype this executor handles. Used as the multibind key. */
    val actionClass: Class<A>

    /**
     * Execute [action] within [context]. Returns [Outcome.Ok] on success, [Outcome.Err] with a
     * typed reason on failure. The engine decides whether to continue based on
     * [Action.continueOnError].
     */
    suspend fun execute(action: A, context: ExecutionContext): Outcome<Unit, ExecutionError>
}

/**
 * Per-execution state. One instance is created when a flow fires; it travels through all
 * actions and exposes:
 *  - the [Flow] being executed
 *  - the originating event (if any) — for [Trigger.NotificationPosted], the matched event +
 *    SBN key so [Action.ClickNotificationAction] can look up the SBN in the cache
 *  - a mutable [variables] map for magic-text resolution. Pre-populated with `notification.*`
 *    bindings from the trigger event.
 *  - an execution id useful in logs ("exec=ab12cd34")
 */
data class ExecutionContext(
    val executionId: String,
    val flow: Flow,
    val triggerEvent: TriggerEvent,
    val variables: MutableMap<String, String>,
)

/** Discriminated union of "what fired this execution". */
sealed interface TriggerEvent {

    data class NotificationFired(
        val event: NotificationEvent,
        /** SBN.key — used by ClickNotificationAction to look up the live notification. */
        val sbnKey: String,
    ) : TriggerEvent

    /**
     * A scheduled trigger fired ([Trigger.TimeOfDay] or [Trigger.Interval]).
     *
     * Magic-text bindings (populated by [FlowEngine] into the execution scope):
     *  - `{trigger.firedAtMillis}` — wall-clock ms when the alarm fired.
     *  - `{trigger.kind}` — "TimeOfDay" or "Interval".
     *  - `{trigger.hour}`, `{trigger.minute}` — local-time HH:MM at fire moment.
     *  - `{trigger.dayOfWeek}` — MON/TUE/...
     */
    data class ScheduledTime(
        val firedAtMillis: Long,
        val triggerKind: String,
    ) : TriggerEvent

    /**
     * An HTTP request arrived at the webhook server.
     *
     * Magic-text bindings:
     *  - `{webhook.method}`, `{webhook.path}`, `{webhook.body}`
     *  - `{webhook.header.<name>}` — case-insensitive header lookup
     *  - `{webhook.query.<name>}` — query string parameter
     */
    data class WebhookReceived(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val query: Map<String, String>,
        val body: String,
    ) : TriggerEvent

    /**
     * An MQTT message arrived on a subscribed topic.
     *
     * Magic-text bindings:
     *  - `{mqtt.topic}` — the topic the message was published to (may include concrete wildcarded segments)
     *  - `{mqtt.payload}` — message payload decoded as UTF-8
     *  - `{mqtt.qos}` — QoS level (0, 1, or 2) as a string
     */
    data class MqttMessage(
        val topic: String,
        val payload: String,
        val qos: Int,
    ) : TriggerEvent
}

/** Typed reasons an action execution can fail. */
sealed interface ExecutionError {
    data class PermissionMissing(val permission: String) : ExecutionError
    data class TargetNotFound(val what: String) : ExecutionError
    data class Timeout(val afterMillis: Long) : ExecutionError
    data class InvalidParam(val name: String, val reason: String) : ExecutionError
    data class SystemFailure(val message: String) : ExecutionError
    data class MagicTextFailed(val expression: String, val reason: String) : ExecutionError
}

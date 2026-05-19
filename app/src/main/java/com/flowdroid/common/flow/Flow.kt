package com.flowdroid.common.flow

/**
 * A single user-defined automation. Phase 1 MVP shape: one trigger + ordered list of actions.
 *
 * No branching, no graph yet — those are deferred to Phase 1.x. The shape is intentionally
 * narrow so it's trivial to extend without breaking the persisted form: add new [Trigger] or
 * [Action] subtypes and bump the JSON schema version.
 *
 * @property id          Stable ID; UUID assigned on creation.
 * @property name        User-visible flow name. Required, non-empty.
 * @property enabled     If false, the engine skips this flow even if its trigger matches.
 * @property trigger     Exactly one trigger; the engine evaluates incoming events against it.
 * @property actions     Ordered list of actions; engine executes head-to-tail, abort on first
 *                       fatal error unless the action's [Action.continueOnError] is true.
 * @property notes       Free-form user notes. Not used by the engine.
 * @property createdAt   Wall-clock millis of creation.
 * @property updatedAt   Wall-clock millis of last edit.
 */
data class Flow(
    val id: String,
    val name: String,
    val enabled: Boolean,
    /**
     * One OR more triggers. Engine fires the action list as soon as ANY trigger matches the
     * incoming event. Each trigger has its own debounce window applied per-flow.
     *
     * Phase 1 MVP shipped with a single trigger; Phase 2 widens this to a list. The on-disk
     * shape is a JSON array of trigger surrogates, controlled by [com.flowdroid.data.db.FlowSerialization].
     */
    val triggers: List<Trigger>,
    val actions: List<Action>,
    val notes: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
) {
    /**
     * Convenience for callers (notably the integration tests) that only configure one trigger.
     * Returns the first trigger or throws if the list is empty (which the editor never produces).
     */
    val trigger: Trigger get() = triggers.firstOrNull()
        ?: throw IllegalStateException("Flow $id has no triggers")
}

/**
 * What starts a flow. Phase 1 MVP: only [NotificationPosted].
 *
 * Plain interface (not `sealed`) so test code can declare ad-hoc subtypes for engine tests.
 * The serializer uses a closed set of `@SerialName`'d surrogate types in
 * `com.flowdroid.data.db.FlowSerialization` — that is what bounds the on-disk shape.
 *
 * Implementations MUST be `data class` so equality / hashing / serialization work consistently.
 */
interface Trigger {

    /**
     * Optional user-facing label. When non-blank the editor shows it in the trigger's card
     * header instead of a generic "Trigger 1 — Notification posted" — useful when a flow has
     * several similar triggers.
     */
    val label: String? get() = null

    /**
     * Fires when a notification matching the filters is observed by the listener.
     *
     * All filters are AND'd. A null filter is treated as "match anything".
     *
     * @property packageName       Exact package name match (e.g. "com.example.app"). Null = any.
     * @property titleRegex        Regex matched against [com.flowdroid.common.domain.NotificationEvent.title].
     * @property textRegex         Regex matched against the notification's text or bigText.
     * @property actionLabelRegex  Regex matched against any of the notification's action labels —
     *                             use this to require that the notification HAS a button matching
     *                             a pattern (e.g. "^Acknowledge$").
     * @property excludeOngoing    If true, notifications with FLAG_ONGOING_EVENT do not match
     *                             (default true — we rarely want to react to media notifications).
     * @property debounceMillis    Within this window after the previous fire of *this same flow*,
     *                             a second match is ignored. Default 1500ms.
     * @property label             User-facing label.
     */
    data class NotificationPosted(
        val packageName: String? = null,
        val titleRegex: String? = null,
        val textRegex: String? = null,
        val actionLabelRegex: String? = null,
        val excludeOngoing: Boolean = true,
        val debounceMillis: Long = 1500L,
        override val label: String? = null,
    ) : Trigger

    /**
     * Fire at a specific time of day on selected weekdays. Time is interpreted in the device's
     * local timezone. Empty `daysOfWeek` is treated as "every day".
     *
     * Implementation note: this is armed by `TimeScheduler` via `AlarmManager.setExactAndAllowWhileIdle`.
     * The receiver re-schedules the next occurrence after firing.
     */
    data class TimeOfDay(
        val hour: Int,                       // 0..23
        val minute: Int,                     // 0..59
        val daysOfWeek: Set<DayOfWeek> = emptySet(),
        override val label: String? = null,
    ) : Trigger

    /**
     * Fire every [intervalMinutes] minutes, optionally only within an active window.
     *
     * - intervalMinutes ≥ 15: backed by WorkManager's PeriodicWorkRequest (battery-friendly).
     * - intervalMinutes < 15: backed by AlarmManager's setRepeating (less battery-friendly,
     *   may be throttled on aggressive OEMs — documented in the editor).
     */
    data class Interval(
        val intervalMinutes: Int,            // 1..1440
        val activeWindow: TimeRange? = null,
        override val label: String? = null,
    ) : Trigger

    /**
     * Fire when an HTTP request arrives at a unique local path.
     *
     * The path is auto-generated when the trigger is created (e.g. "hook-ab12cd34") so it's
     * unique within this device. If [secret] is non-blank, the request must include
     * `X-FlowDroid-Secret: <secret>` — else the server returns 401.
     *
     * Server binds on 127.0.0.1:8821. For remote access the user sets up a tunnel
     * (Tailscale, Cloudflare Tunnel) or `adb reverse`.
     */
    data class Webhook(
        val path: String,
        val method: WebhookMethod = WebhookMethod.ANY,
        val secret: String? = null,
        override val label: String? = null,
    ) : Trigger
}

enum class DayOfWeek { MON, TUE, WED, THU, FRI, SAT, SUN }
enum class WebhookMethod { GET, POST, PUT, ANY }

/**
 * Half-open time-of-day window, e.g. 08:00..18:00. Used by [Trigger.Interval.activeWindow] to
 * drop fires outside business hours.
 */
data class TimeRange(
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
)

/**
 * What a flow does. Phase 1 MVP: three concrete kinds.
 *
 * Plain interface (not `sealed`) — same rationale as [Trigger]: lets the engine tests declare
 * ad-hoc Action subtypes, while the on-disk serial form is still bounded by surrogate types.
 *
 * @property continueOnError If true, the engine moves on to the next action even if this one
 *                           reported a failure. Default false (fail-fast).
 */
interface Action {

    val continueOnError: Boolean

    /**
     * Optional user-facing label. When non-blank the editor's action card header shows
     * `"<label> · <type>"` instead of just `<type>` — useful for telling apart several
     * `LaunchApp` or `UiClick` actions in a long flow. Stored verbatim, no magic-text expansion.
     */
    val label: String? get() = null

    data class ClickNotificationAction(
        val labelRegex: String,
        val dismissAfter: Boolean = false,
        override val continueOnError: Boolean = false,
        override val label: String? = null,
    ) : Action

    data class LaunchApp(
        val packageName: String,
        val activityClass: String? = null,
        override val continueOnError: Boolean = false,
        override val label: String? = null,
    ) : Action

    data class PostNotification(
        val title: String,
        val text: String,
        override val continueOnError: Boolean = true,
        override val label: String? = null,
    ) : Action

    data class Delay(
        val millis: Long,
        override val continueOnError: Boolean = false,
        override val label: String? = null,
    ) : Action

    data class KillApp(
        val packageName: String,
        override val continueOnError: Boolean = false,
        override val label: String? = null,
    ) : Action

    data class UiClick(
        val targetMode: UiTargetMode,
        val x: Float = 0f,
        val y: Float = 0f,
        val text: String? = null,
        val textMatch: TextMatch = TextMatch.EXACT,
        val contentDescription: String? = null,
        val viewId: String? = null,
        val inPackage: String? = null,
        val clickMode: ClickMode = ClickMode.SINGLE,
        val longPressDurationMs: Long = 600L,
        val timeoutMs: Long = 5000L,
        override val continueOnError: Boolean = false,
        override val label: String? = null,
    ) : Action

    data class UiSwipe(
        val direction: SwipeDirection? = null,
        val fromX: Float = 0f,
        val fromY: Float = 0f,
        val toX: Float = 0f,
        val toY: Float = 0f,
        val durationMs: Long = 300L,
        override val continueOnError: Boolean = false,
        override val label: String? = null,
    ) : Action

    data class UiTypeText(
        val text: String,
        val pressEnterAfter: Boolean = false,
        override val continueOnError: Boolean = false,
        override val label: String? = null,
    ) : Action

    data class UiPressKey(
        val key: UiKey,
        override val continueOnError: Boolean = false,
        override val label: String? = null,
    ) : Action

    /**
     * Make an HTTPS request — works while the device is locked because it's pure network I/O
     * with no UI dependency. All string fields support magic text so the URL, header values, and
     * body can interpolate notification data and earlier-action variables.
     *
     * @property method                HTTP verb.
     * @property url                   Full URL. Magic-text aware.
     * @property headers               Sent in order — preserves cases where order matters.
     * @property body                  Request body. Null for GET/DELETE.
     * @property bodyContentType       Drives the `Content-Type` header on send.
     * @property timeoutMs             Per-call hard ceiling. Action fails with `Timeout` beyond.
     * @property expectStatusMin/Max   Acceptable status code range. Outside this range the
     *                                 action returns `SystemFailure` with a body excerpt for diagnostics.
     * @property storeStatusInVar      If non-null, writes the response status code into this var.
     * @property storeBodyInVar        If non-null, writes the response body string into this var.
     *                                 Subsequent actions can reference it via `{var.<name>}`.
     */
    data class Http(
        val method: HttpMethod,
        val url: String,
        val headers: List<HttpHeader> = emptyList(),
        val body: String? = null,
        val bodyContentType: HttpBodyType = HttpBodyType.NONE,
        val timeoutMs: Long = 30_000L,
        val expectStatusMin: Int = 200,
        val expectStatusMax: Int = 299,
        val storeStatusInVar: String? = null,
        val storeBodyInVar: String? = null,
        /**
         * Number of extra attempts after the first try. `0` (default) preserves the legacy
         * single-shot behaviour — existing flows are not affected.
         *
         * Retry triggers:
         *  - any network-layer error (IOException including SocketTimeoutException)
         *  - a response status code inside the retryOnStatus range
         *
         * A status that's outside the expect-status range but ALSO outside the retry range still
         * fails immediately (e.g. 404 with the default retry range of 500..599).
         *
         * Capped internally at [MAX_RETRIES] to keep total wall-time bounded against the engine's
         * 30-second per-action ceiling.
         */
        val retries: Int = 0,
        /**
         * Base for the exponential backoff between retries: attempt-N waits `retryBackoffMs * 2^N`
         * milliseconds, capped at [MAX_BACKOFF_MS]. Keep low (250–1500ms) so the total wall-time
         * stays inside the engine's per-action timeout.
         */
        val retryBackoffMs: Long = 1_000L,
        val retryOnStatusMin: Int = 500,
        val retryOnStatusMax: Int = 599,
        override val continueOnError: Boolean = false,
        override val label: String? = null,
    ) : Action {
        companion object {
            const val MAX_RETRIES: Int = 5
            const val MAX_BACKOFF_MS: Long = 10_000L
        }
    }

    /**
     * Assign a magic-text-resolved value to a named variable in the execution scope. Later actions
     * can reference it as `{var.<name>}`. The variable name itself is NOT magic-text expanded.
     */
    data class SetVariable(
        val name: String,
        val value: String,
        override val continueOnError: Boolean = false,
        override val label: String? = null,
    ) : Action

    /**
     * Read a file from app-private storage into a variable. Path is a relative name inside the
     * app's private `automation/` directory — sandboxed; `..` segments are rejected. Use
     * [WriteFile] to populate this directory from earlier flow runs (or push files via `adb push`).
     */
    data class ReadFile(
        val path: String,
        val intoVar: String,
        override val continueOnError: Boolean = false,
        override val label: String? = null,
    ) : Action

    /**
     * Write magic-text-expanded [content] to app-private storage. Same sandbox rules as [ReadFile].
     * [mode] selects between overwrite (default) and append.
     */
    data class WriteFile(
        val path: String,
        val content: String,
        val mode: FileWriteMode = FileWriteMode.OVERWRITE,
        override val continueOnError: Boolean = false,
        override val label: String? = null,
    ) : Action

    /**
     * Base64 encode or decode [input]. Encoded output uses RFC 4648 with no line wrapping;
     * decode rejects malformed input with [com.flowdroid.common.flow.ExecutionError.InvalidParam].
     */
    data class Base64(
        val input: String,
        val mode: Base64Mode,
        val intoVar: String,
        override val continueOnError: Boolean = false,
        override val label: String? = null,
    ) : Action

    /** Compute a cryptographic hash of [input]. Output is lowercase hex. */
    data class Hash(
        val input: String,
        val algorithm: HashAlgorithm,
        val intoVar: String,
        override val continueOnError: Boolean = false,
        override val label: String? = null,
    ) : Action

    /**
     * Conditional execution. Evaluate [condition] against the current execution variables; on
     * `true` run [thenActions] in order, on `false` run [elseActions]. Sub-actions execute in the
     * same execution context — variables set inside the branch are visible to subsequent actions
     * in the parent flow (this is intentional, mirrors how most automation tools handle it).
     *
     * The branch's actions follow the same `continueOnError` semantics as top-level actions: a
     * sub-action that errors and is not `continueOnError` aborts the BRANCH (not the parent flow,
     * unless the If itself has `continueOnError = false` and propagates).
     */
    data class If(
        val condition: Condition,
        val thenActions: List<Action> = emptyList(),
        val elseActions: List<Action> = emptyList(),
        override val continueOnError: Boolean = false,
        override val label: String? = null,
    ) : Action

    /**
     * Repeated execution of [actions].
     *
     * - `COUNT` runs [actions] exactly [count] times.
     * - `FOREACH_LINES` magic-text-expands [list] and splits on `\n`; each non-empty trimmed line
     *   becomes one iteration. This is the natural shape when an upstream HTTP action returns a
     *   newline-separated list, or when the user has a `WriteFile` log accumulating IDs.
     *
     * Per-iteration bindings (overwritten each loop):
     *  - `{var.<itemVar>}` — the current line content (FOREACH_LINES) or empty (COUNT).
     *  - `{var.<indexVar>}` — zero-based iteration index as a string.
     *
     * Sub-actions share the parent's [ExecutionContext]; variable writes persist across iterations
     * and into the parent flow. Hard safety cap: [MAX_ITERATIONS] — beyond that the executor
     * aborts with `SystemFailure` rather than risk a runaway loop on a malformed flow.
     */
    data class Loop(
        val mode: LoopMode = LoopMode.COUNT,
        val count: Int = 1,
        val list: String = "",
        val itemVar: String = "item",
        val indexVar: String = "index",
        val actions: List<Action> = emptyList(),
        override val continueOnError: Boolean = false,
        override val label: String? = null,
    ) : Action {
        companion object {
            const val MAX_ITERATIONS: Int = 1000
        }
    }

    /**
     * Try / Catch / Finally semantics.
     *
     *  - [tryActions] run first. If any sub-action aborts (a non-`continueOnError` failure) OR
     *    the sub-list ends with `errCount > 0`, the catch path is taken.
     *  - [catchActions] run only if the try path failed. They see all variables the try path
     *    wrote up to the failure point (the catch can therefore inspect partial state).
     *  - [finallyActions] always run after either try or catch, even if catch itself failed. This
     *    mirrors Java/Kotlin semantics and is the right place to put cleanup like "delete temp
     *    file" or "post completion notification".
     *
     *  - [intoErrorVar] is optional: when non-blank, the engine sets `{var.<intoErrorVar>}` to a
     *    short string describing why the try path failed (e.g. `"action_aborted"` or
     *    `"errors=2"`). Blank = no capture.
     *
     * The TryCatch action itself returns `Outcome.Ok` whenever it executes any branch without an
     * unexpected throw — the whole point is to ABSORB sub-failures so the parent flow keeps going.
     */
    data class TryCatch(
        val tryActions: List<Action> = emptyList(),
        val catchActions: List<Action> = emptyList(),
        val finallyActions: List<Action> = emptyList(),
        val intoErrorVar: String = "",
        override val continueOnError: Boolean = true,
        override val label: String? = null,
    ) : Action

    /**
     * Wake the screen and dismiss the keyguard, where the device's lock policy permits it.
     *
     * Use this as the first action of a flow that runs UI gestures so the gestures don't fail
     * with "screen off" / "keyguard intercepted". HTTP / notification actions don't need it —
     * they run fine while the device is locked.
     *
     * Behaviour matrix:
     *  - **No PIN / pattern / password configured** → wakes screen, dismisses keyguard silently
     *    → [com.flowdroid.common.Outcome.Ok]. This is the only fully-automatic path.
     *  - **Credential set, user is present (screen on, watching)** → Android shows its standard
     *    unlock prompt; if the user enters the credential within [timeoutMs], Ok. Otherwise
     *    [com.flowdroid.common.flow.ExecutionError.Timeout].
     *  - **Credential set, user not present** → request is denied or times out;
     *    [com.flowdroid.common.flow.ExecutionError.SystemFailure]. There is no public Android
     *    API that lets a background app skip a configured credential — this is by design.
     *  - **Already unlocked** → no-op → Ok.
     */
    data class UnlockScreen(
        val timeoutMs: Long = 5_000L,
        override val continueOnError: Boolean = false,
        override val label: String? = null,
    ) : Action
}

enum class LoopMode { COUNT, FOREACH_LINES }

/**
 * A boolean test used by [Action.If]. Both sides are magic-text expanded at evaluation time.
 * Unary ops (`IS_BLANK`, `IS_NOT_BLANK`) ignore [right].
 */
data class Condition(
    val left: String,
    val op: CompareOp,
    val right: String = "",
)

/**
 * Comparison operators for [Condition]. Numeric ops (GT/LT/GTE/LTE) parse both sides as `Double`
 * and return false if either side fails to parse. REGEX matches [right] as a Kotlin regex against
 * [left]; an invalid pattern is treated as a no-match and logged.
 */
enum class CompareOp {
    EQUALS, NOT_EQUALS, CONTAINS, STARTS_WITH, ENDS_WITH, REGEX,
    GT, LT, GTE, LTE,
    IS_BLANK, IS_NOT_BLANK,
}

enum class FileWriteMode { OVERWRITE, APPEND }
enum class Base64Mode { ENCODE, DECODE }
enum class HashAlgorithm { MD5, SHA1, SHA256, SHA512 }

enum class HttpMethod { GET, POST, PUT, DELETE, PATCH }
enum class HttpBodyType { NONE, TEXT, JSON, FORM }

/**
 * One HTTP header entry. Stored as an ordered list rather than a map so users can deliberately
 * order auth/accept/content-type headers, and so the on-disk shape is a deterministic JSON array.
 *
 * Both [name] and [value] are magic-text aware at execute time.
 */
data class HttpHeader(val name: String, val value: String)

/** How [Action.UiClick] resolves the target node. */
enum class UiTargetMode { COORDINATES, BY_TEXT, BY_CONTENT_DESCRIPTION, BY_VIEW_ID }

/** Single tap, two taps in quick succession, or held tap. */
enum class ClickMode { SINGLE, DOUBLE, LONG }

/** Text comparison strategies for BY_TEXT mode. */
enum class TextMatch { EXACT, CONTAINS, REGEX, STARTS_WITH }

/** Cardinal directions for [Action.UiSwipe] in `direction` mode. */
enum class SwipeDirection { UP, DOWN, LEFT, RIGHT }

/** Global navigation keys supported by [Action.UiPressKey]. */
enum class UiKey { BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, ENTER, POWER_DIALOG, LOCK_SCREEN }

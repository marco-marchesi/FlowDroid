package com.flowdroid.common.flow

import com.flowdroid.common.Outcome

/**
 * Evaluates `{path}` placeholders against a variable map.
 *
 * Syntax (Phase 1):
 *  - `{var.name}` → reads from the variables map by the path "var.name"
 *  - `{notification.title}` → same: just a path lookup
 *  - `{notification.action[0].label}` → indexed lookup: variables map key "notification.action[0].label"
 *  - `\{escaped\}` → renders literally as `{escaped}`
 *  - Unknown path → expands to empty string and produces a warning in the result
 *
 * No infix operators, no function calls — that's the expression engine (Phase 3).
 *
 * Performance: the engine is ~1µs per expansion; the test suite asserts 1000 expansions in
 * < 50 ms.
 */
interface MagicTextEngine {
    /**
     * Expand placeholders in [template] using [variables]. Returns the expanded text plus
     * any warnings (unknown keys, malformed braces). Never throws.
     */
    fun expand(template: String, variables: Map<String, String>): MagicTextResult
}

data class MagicTextResult(
    val text: String,
    val warnings: List<String>,
)

/**
 * Convenience: throw-friendly variant used by callers that prefer Outcome semantics — though
 * MagicText itself never fails per se; this exists so call sites can chain with `flatMap`.
 */
fun MagicTextEngine.expandOrEmpty(template: String?, variables: Map<String, String>): String {
    if (template.isNullOrEmpty()) return ""
    return expand(template, variables).text
}

/**
 * Standard binding keys exposed by the engine. Centralised so triggers and actions agree
 * on the same names.
 */
object MagicTextBindings {
    const val NOTIF_PACKAGE = "notification.package"
    const val NOTIF_TITLE = "notification.title"
    const val NOTIF_TEXT = "notification.text"
    const val NOTIF_BIG_TEXT = "notification.bigText"
    const val NOTIF_SUB_TEXT = "notification.subText"
    const val NOTIF_CHANNEL = "notification.channel"
    const val NOTIF_ID = "notification.id"
    const val NOTIF_KEY = "notification.key"
    const val NOTIF_POST_TIME = "notification.postTime"
    const val NOTIF_GROUP_KEY = "notification.groupKey"
    const val NOTIF_TICKER = "notification.tickerText"
    /**
     * Raw notification extras as a best-effort JSON dump (matches
     * [com.flowdroid.common.domain.NotificationEvent.rawExtrasJson]). Use with `|jsonpath:$.X` or
     * `|regex:PATTERN` to pull fields the visible message doesn't expose — e.g. FCM-data payload
     * fields like `match_id` that don't appear in title/text/bigText.
     */
    const val NOTIF_RAW_EXTRAS = "notification.rawExtras"
    /** Per-action label binding pattern: `notification.action[N].label`. */
    fun notifActionLabel(index: Int) = "notification.action[$index].label"

    const val FLOW_ID = "flow.id"
    const val FLOW_NAME = "flow.name"
    const val EXEC_ID = "exec.id"
}

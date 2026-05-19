package com.flowdroid.ui.flows

import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.Trigger

/**
 * Catalogue of triggers and actions, grouped into logical families with searchable metadata.
 *
 * This drives the "Add trigger" and "Add action" pickers: families are rendered as section
 * headers, each containing rows of selectable kinds. A search field at the top filters across
 * all families by name, keywords, and family label.
 *
 * Pure data — no Android imports — so it's trivial to keep tests fast.
 */
object ActionCatalog {

    enum class TriggerFamily(val label: String) {
        APPS_NOTIFICATIONS("Apps & Notifications"),
    }

    /**
     * Action families. Order here is the display order in the picker.
     */
    enum class ActionFamily(val label: String) {
        NOTIFICATIONS("Notifications"),
        APPS("Apps"),
        UI_INTERACTION("UI interaction"),
        TIMING("Timing"),
        NETWORK("Network"),
        VARIABLES("Variables"),
        DATA("Data & files"),
        LOGIC("Logic & flow"),
    }

    /**
     * A single selectable action kind.
     *
     * @property name          User-visible name shown as the row label.
     * @property description   One-line hint shown under the name.
     * @property family        Section header it belongs to.
     * @property keywords      Extra terms the search index will match against beyond [name].
     * @property factory       Produces a freshly-initialised [Action] of this kind for the editor
     *                         to start from.
     */
    data class ActionEntry(
        val id: String,
        val name: String,
        val description: String,
        val family: ActionFamily,
        val keywords: List<String>,
        val factory: () -> Action,
    )

    data class TriggerEntry(
        val id: String,
        val name: String,
        val description: String,
        val family: TriggerFamily,
        val keywords: List<String>,
        val factory: () -> Trigger,
    )

    /** Complete list of action kinds, ordered by family then alphabetically within family. */
    val actions: List<ActionEntry> = listOf(
        // ── Notifications ────────────────────────────────────────────────────
        ActionEntry(
            id = "click_notif",
            name = "Click notification button",
            description = "Fire one of the notification's action buttons (e.g. Reply, Acknowledge).",
            family = ActionFamily.NOTIFICATIONS,
            keywords = listOf("tap", "press", "join", "acknowledge", "action button"),
            factory = { Action.ClickNotificationAction(labelRegex = "") },
        ),
        ActionEntry(
            id = "post_notif",
            name = "Post notification",
            description = "Show a notification (title/text supports magic text).",
            family = ActionFamily.NOTIFICATIONS,
            keywords = listOf("show", "alert", "remind", "notify"),
            factory = { Action.PostNotification(title = "", text = "") },
        ),
        // ── Apps ─────────────────────────────────────────────────────────────
        ActionEntry(
            id = "launch_app",
            name = "Launch app",
            description = "Start an app (or a specific activity).",
            family = ActionFamily.APPS,
            keywords = listOf("start", "open", "run"),
            factory = { Action.LaunchApp(packageName = "") },
        ),
        ActionEntry(
            id = "kill_app",
            name = "Kill app",
            description = "Stop an app's background processes.",
            family = ActionFamily.APPS,
            keywords = listOf("close", "stop", "force stop", "terminate"),
            factory = { Action.KillApp(packageName = "") },
        ),
        // ── UI interaction ───────────────────────────────────────────────────
        ActionEntry(
            id = "ui_tap",
            name = "UI tap",
            description = "Tap, double-tap or long-press a UI element (by text, id, coords).",
            family = ActionFamily.UI_INTERACTION,
            keywords = listOf("click", "press", "touch", "double tap", "long press", "accessibility"),
            factory = {
                Action.UiClick(targetMode = com.flowdroid.common.flow.UiTargetMode.BY_TEXT)
            },
        ),
        ActionEntry(
            id = "ui_swipe",
            name = "UI swipe",
            description = "Swipe up / down / left / right or between two points.",
            family = ActionFamily.UI_INTERACTION,
            keywords = listOf("scroll", "drag", "fling", "gesture"),
            factory = {
                Action.UiSwipe(direction = com.flowdroid.common.flow.SwipeDirection.UP)
            },
        ),
        ActionEntry(
            id = "ui_type",
            name = "Type text",
            description = "Type into the focused field (optionally press Enter after).",
            family = ActionFamily.UI_INTERACTION,
            keywords = listOf("enter", "input", "keyboard", "fill", "ime"),
            factory = { Action.UiTypeText(text = "") },
        ),
        ActionEntry(
            id = "ui_key",
            name = "Press key",
            description = "Back / Home / Recents / Notifications / Quick settings / Enter / Power.",
            family = ActionFamily.UI_INTERACTION,
            keywords = listOf("button", "navigation", "back", "home", "enter", "lock", "power"),
            factory = { Action.UiPressKey(key = com.flowdroid.common.flow.UiKey.BACK) },
        ),
        ActionEntry(
            id = "unlock_screen",
            name = "Unlock screen",
            description = "Wake screen + dismiss keyguard. Works silently only if no PIN/pattern is set.",
            family = ActionFamily.UI_INTERACTION,
            keywords = listOf("unlock", "wake", "screen", "keyguard", "lock", "dismiss", "wakeup"),
            factory = { Action.UnlockScreen() },
        ),
        // ── Timing ───────────────────────────────────────────────────────────
        ActionEntry(
            id = "delay",
            name = "Delay",
            description = "Pause for a fixed duration before continuing.",
            family = ActionFamily.TIMING,
            keywords = listOf("wait", "sleep", "pause", "timeout"),
            factory = { Action.Delay(millis = 500L) },
        ),
        // ── Network ──────────────────────────────────────────────────────────
        ActionEntry(
            id = "http_get",
            name = "HTTP GET",
            description = "Send a GET request. Store status/body for the next action. Works while locked.",
            family = ActionFamily.NETWORK,
            keywords = listOf("webhook", "api", "fetch", "rest", "request", "url"),
            factory = {
                Action.Http(
                    method = com.flowdroid.common.flow.HttpMethod.GET,
                    url = "",
                )
            },
        ),
        ActionEntry(
            id = "http_post",
            name = "HTTP POST (JSON)",
            description = "Send a JSON POST. Auth via Cookie/Authorization header. Works while locked.",
            family = ActionFamily.NETWORK,
            keywords = listOf("webhook", "api", "post", "subscribe", "json", "rest", "ifttt"),
            factory = {
                Action.Http(
                    method = com.flowdroid.common.flow.HttpMethod.POST,
                    url = "",
                    body = "{}",
                    bodyContentType = com.flowdroid.common.flow.HttpBodyType.JSON,
                )
            },
        ),
        ActionEntry(
            id = "http_form",
            name = "HTTP form POST",
            description = "POST with application/x-www-form-urlencoded body.",
            family = ActionFamily.NETWORK,
            keywords = listOf("form", "post", "submit", "urlencoded"),
            factory = {
                Action.Http(
                    method = com.flowdroid.common.flow.HttpMethod.POST,
                    url = "",
                    body = "",
                    bodyContentType = com.flowdroid.common.flow.HttpBodyType.FORM,
                )
            },
        ),
        // ── Variables ────────────────────────────────────────────────────────
        ActionEntry(
            id = "set_var",
            name = "Set variable",
            description = "Store a string into a named variable for later actions to reuse.",
            family = ActionFamily.VARIABLES,
            keywords = listOf("set", "var", "assign", "store", "extract", "jsonpath", "regex"),
            factory = { Action.SetVariable(name = "", value = "") },
        ),
        // ── Data & files ─────────────────────────────────────────────────────
        ActionEntry(
            id = "read_file",
            name = "Read file",
            description = "Read a file from app-private storage into a variable.",
            family = ActionFamily.DATA,
            keywords = listOf("file", "load", "open", "cat", "storage", "automation"),
            factory = { Action.ReadFile(path = "", intoVar = "") },
        ),
        ActionEntry(
            id = "write_file",
            name = "Write file",
            description = "Write content to app-private storage (overwrite or append).",
            family = ActionFamily.DATA,
            keywords = listOf("file", "save", "log", "append", "overwrite", "storage"),
            factory = {
                Action.WriteFile(
                    path = "",
                    content = "",
                    mode = com.flowdroid.common.flow.FileWriteMode.OVERWRITE,
                )
            },
        ),
        ActionEntry(
            id = "base64",
            name = "Base64",
            description = "Encode or decode a string with Base64.",
            family = ActionFamily.DATA,
            keywords = listOf("encode", "decode", "b64", "rfc4648", "convert"),
            factory = {
                Action.Base64(
                    input = "",
                    mode = com.flowdroid.common.flow.Base64Mode.ENCODE,
                    intoVar = "",
                )
            },
        ),
        ActionEntry(
            id = "hash",
            name = "Hash",
            description = "Compute an MD5/SHA-1/SHA-256/SHA-512 hash of a string.",
            family = ActionFamily.DATA,
            keywords = listOf("digest", "md5", "sha", "sha1", "sha256", "sha256", "checksum"),
            factory = {
                Action.Hash(
                    input = "",
                    algorithm = com.flowdroid.common.flow.HashAlgorithm.SHA256,
                    intoVar = "",
                )
            },
        ),
        // ── Logic & flow ─────────────────────────────────────────────────────
        ActionEntry(
            id = "if",
            name = "If / Else",
            description = "Run one branch of actions if a condition matches, otherwise the other.",
            family = ActionFamily.LOGIC,
            keywords = listOf(
                "branch", "condition", "compare", "switch", "when", "then", "else",
                "equals", "regex", "gt", "lt", "greater", "less", "boolean",
            ),
            factory = {
                Action.If(
                    condition = com.flowdroid.common.flow.Condition(
                        left = "",
                        op = com.flowdroid.common.flow.CompareOp.EQUALS,
                        right = "",
                    ),
                )
            },
        ),
        ActionEntry(
            id = "loop",
            name = "Loop / Foreach",
            description = "Repeat a sub-list N times or once per line of an upstream value.",
            family = ActionFamily.LOGIC,
            keywords = listOf(
                "loop", "repeat", "foreach", "iterate", "for", "while", "times", "list",
            ),
            factory = {
                Action.Loop(
                    mode = com.flowdroid.common.flow.LoopMode.COUNT,
                    count = 3,
                )
            },
        ),
        ActionEntry(
            id = "try_catch",
            name = "Try / Catch / Finally",
            description = "Run a block, fall back to catch on failure, always run finally last.",
            family = ActionFamily.LOGIC,
            keywords = listOf(
                "try", "catch", "finally", "error", "fallback", "recover", "handle", "exception",
            ),
            factory = { Action.TryCatch() },
        ),
    )

    /** Complete list of trigger kinds. Phase 2 has one. */
    val triggers: List<TriggerEntry> = listOf(
        TriggerEntry(
            id = "notification_posted",
            name = "Notification posted",
            description = "Match notifications by package, title, text or action label.",
            family = TriggerFamily.APPS_NOTIFICATIONS,
            keywords = listOf("notif", "alert", "shade", "filter", "listener"),
            factory = { Trigger.NotificationPosted() },
        ),
    )

    /**
     * Filter [actions] by a free-text query. Empty query returns all. Match is case-insensitive
     * against the name, description, keywords, and family label.
     */
    fun searchActions(query: String): List<ActionEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return actions
        return actions.filter { it.matches(q) }
    }

    fun searchTriggers(query: String): List<TriggerEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return triggers
        return triggers.filter { it.matches(q) }
    }

    private fun ActionEntry.matches(q: String): Boolean =
        name.lowercase().contains(q) ||
            description.lowercase().contains(q) ||
            family.label.lowercase().contains(q) ||
            keywords.any { it.lowercase().contains(q) }

    private fun TriggerEntry.matches(q: String): Boolean =
        name.lowercase().contains(q) ||
            description.lowercase().contains(q) ||
            family.label.lowercase().contains(q) ||
            keywords.any { it.lowercase().contains(q) }
}

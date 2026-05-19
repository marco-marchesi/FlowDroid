package com.flowdroid.data.flows

import android.content.Context
import com.flowdroid.common.StructuredLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Curated starter flows bundled with the APK. Each template is a single-flow JSON payload that
 * the existing [FlowImportExport.importFlows] knows how to decode — the user gets a fresh UUID,
 * `enabled = false`, and fresh `createdAt` / `updatedAt` exactly like an external import.
 *
 * Why hardcoded rather than scanning assets/templates/ at runtime: the list is short, ordering
 * matters for the UI, and each entry carries a title + description that the gallery sheet shows.
 * Adding a new template is a one-line edit here plus one new asset file under
 * `app/src/main/assets/templates/`.
 *
 * Templates were chosen to cover every shipped feature surface (triggers + each action family +
 * magic-text modifiers + control-flow constructs) so the user can install them on a fresh device
 * and walk through the app exercising every code path.
 */
@Singleton
class TemplateGallery @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: StructuredLogger,
) {

    data class Template(
        val id: String,
        val title: String,
        val description: String,
        /** Asset path relative to `app/src/main/assets/`. */
        val asset: String,
    )

    /**
     * Ordered for the picker. IDs and titles are contiguous 01..12; asset filenames retain
     * their original numeric prefixes because they're shipped as bundled assets and renaming
     * them on disk would invalidate user-installed copies.
     */
    val templates: List<Template> = listOf(
        Template(
            id = "01_hello_world",
            title = "01 · Hello world",
            description = "Any notification → echo back as a new notification. Smallest possible flow.",
            asset = "templates/01-hello-world.json",
        ),
        Template(
            id = "02_daily_reminder",
            title = "02 · Daily morning reminder",
            description = "Fires at 08:30 on weekdays. Tests TimeOfDay trigger and trigger.* magic text.",
            asset = "templates/03-daily-morning-reminder.json",
        ),
        Template(
            id = "03_interval_heartbeat",
            title = "03 · Interval heartbeat (HTTP GET)",
            description = "Every 15 min in waking hours pings httpbin.org and posts the status. Tests Interval + activeWindow.",
            asset = "templates/04-interval-heartbeat.json",
        ),
        Template(
            id = "04_webhook_echo",
            title = "04 · Webhook echo",
            description = "Local HTTP server on /hook/echo posts a notification with the request body. Tests Webhook trigger.",
            asset = "templates/05-webhook-echo.json",
        ),
        Template(
            id = "05_state_file",
            title = "05 · State file persistence",
            description = "Daily noon trigger that read/writes a state file. Tests ReadFile + WriteFile + persistence across runs.",
            asset = "templates/06-state-file-persistence.json",
        ),
        Template(
            id = "06_loop_foreach",
            title = "06 · Loop foreach line",
            description = "Webhook body split into lines, one notification per line. Tests Loop FOREACH_LINES + item/index vars.",
            asset = "templates/07-loop-foreach-lines.json",
        ),
        Template(
            id = "07_loop_count",
            title = "07 · Loop count + Delay",
            description = "Three-tick loop with delays between notifications. Tests Loop COUNT + Delay + nested actions.",
            asset = "templates/08-loop-count-tick.json",
        ),
        Template(
            id = "08_trycatch_flaky",
            title = "08 · TryCatch flaky HTTP",
            description = "Try always-failing HTTP → catch posts fallback → finally appends to a log file. Tests TryCatch full path.",
            asset = "templates/09-trycatch-flaky-http.json",
        ),
        Template(
            id = "09_base64_hash",
            title = "09 · Base64 + SHA-256 pipeline",
            description = "Webhook body → encode → hash → hash-of-hash chain → notification. Tests Base64, Hash, var chaining.",
            asset = "templates/10-base64-and-hash.json",
        ),
        Template(
            id = "10_if_regex_alert",
            title = "10 · If REGEX severity router",
            description = "Regex-match notification title for error/fail/critical → loud alert; else append to benign log. Tests If REGEX + else-path work.",
            asset = "templates/11-if-regex-alert.json",
        ),
        Template(
            id = "11_jsonpath_regex",
            title = "11 · jsonpath + regex modifiers",
            description = "Webhook JSON → extract fields via |jsonpath: and |regex: → notification. Tests the magic-text modifier system.",
            asset = "templates/12-jsonpath-and-regex-extract.json",
        ),
        Template(
            id = "12_notification_probe",
            title = "12 · Notification probe (debug)",
            description = "Dumps every field of incoming notifications to a file + echoes bigText. Use to figure out what data a real notification carries so you can build robust regex/jsonpath patterns.",
            asset = "templates/14-notification-probe.json",
        ),
    )

    /**
     * Load the raw JSON for [template] from assets. Returns null on I/O failure (asset missing,
     * not packaged, encoding issue) — the caller should surface that as a snackbar.
     */
    fun load(template: Template): String? = try {
        context.assets.open(template.asset).use { input ->
            input.bufferedReader(Charsets.UTF_8).readText()
        }
    } catch (t: Throwable) {
        if (t is OutOfMemoryError ||
            t is kotlin.coroutines.cancellation.CancellationException
        ) throw t
        logger.warn(TAG, "template asset load failed", t,
            "id" to template.id,
            "asset" to template.asset)
        null
    }

    companion object {
        private const val TAG = "TemplateGallery"
    }
}

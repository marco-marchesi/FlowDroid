package com.flowdroid.engine

import com.flowdroid.common.domain.NotificationEvent
import com.flowdroid.common.flow.Trigger
import java.util.Collections
import java.util.WeakHashMap
import java.util.regex.PatternSyntaxException

/**
 * Pure predicate that decides whether a [Trigger] matches a given event.
 *
 * Why pure / no Hilt:
 *  - The matcher has zero collaborators; it is just predicate logic over the trigger's filter
 *    fields and the event's data. Object form makes it trivial to unit-test and to share a
 *    single regex cache across the whole process.
 *  - Engine logging is the caller's responsibility — the matcher returns a structured
 *    [MatchOutcome] (Matched or NoMatch(reason)) so the engine can log *why* a flow did not
 *    fire, which is the #1 support question for an automation app.
 *
 * Regex cache:
 *  - Compilation of `Regex` is non-trivial; the engine evaluates the same trigger thousands of
 *    times across a typical day. We memoise compiled patterns keyed on the source string.
 *  - The cache is a synchronised [WeakHashMap] keyed on the *string* — this is intentional:
 *    flows can be edited / deleted at runtime, but the string keys naturally evict if no flow
 *    holds them any more (we don't keep heap references beyond the pattern itself).
 */
object TriggerMatcher {

    /** Result of a single match attempt. */
    sealed interface MatchOutcome {
        data object Matched : MatchOutcome
        data class NoMatch(val reason: String) : MatchOutcome
        /** A regex in the trigger config is unparseable. Treated as no-match upstream. */
        data class InvalidRegex(val field: String, val pattern: String, val message: String) : MatchOutcome
    }

    private val regexCache: MutableMap<String, Regex> =
        Collections.synchronizedMap(WeakHashMap())

    /** Compile + cache. Returns null on PatternSyntaxException. */
    private fun compile(pattern: String): Regex? {
        // Fast path: hit
        val hit = synchronized(regexCache) { regexCache[pattern] }
        if (hit != null) return hit
        return try {
            val r = Regex(pattern)
            synchronized(regexCache) { regexCache[pattern] = r }
            r
        } catch (e: PatternSyntaxException) {
            null
        }
    }

    /**
     * Evaluate [trigger] against [event].
     *
     * Filter semantics (per [Trigger.NotificationPosted] kdoc):
     *  - `packageName`: exact equality, case-sensitive. Null = any.
     *  - `titleRegex`: [Regex.containsMatchIn] against [NotificationEvent.title].
     *    We intentionally use `containsMatchIn`, not `matches`, because users typing
     *    `"Echo"` expect it to match `"Echo notification received"`.
     *  - `textRegex`: same; matches against `text` OR `bigText` (whichever yields a hit).
     *  - `actionLabelRegex`: any element of `actionLabels` matches.
     *  - `excludeOngoing`: if true and the event is ongoing OR a group summary, no match.
     */
    fun matches(trigger: Trigger, event: NotificationEvent): MatchOutcome {
        if (trigger !is Trigger.NotificationPosted) {
            return MatchOutcome.NoMatch("trigger is not NotificationPosted")
        }

        // 1. Package
        if (trigger.packageName != null && trigger.packageName != event.packageName) {
            return MatchOutcome.NoMatch(
                "packageName mismatch: want=${trigger.packageName} got=${event.packageName}"
            )
        }

        // 2. Ongoing / group-summary exclusion (default true in real users' configs).
        if (trigger.excludeOngoing && (event.isOngoing || event.isGroupSummary)) {
            return MatchOutcome.NoMatch("excludeOngoing and event is ongoing/groupSummary")
        }

        // 3. Title regex
        trigger.titleRegex?.let { pat ->
            val re = compile(pat)
                ?: return MatchOutcome.InvalidRegex("titleRegex", pat, "invalid pattern")
            val target = event.title.orEmpty()
            if (!re.containsMatchIn(target)) {
                return MatchOutcome.NoMatch("titleRegex did not match title")
            }
        }

        // 4. Text regex — try text first, fall back to bigText.
        trigger.textRegex?.let { pat ->
            val re = compile(pat)
                ?: return MatchOutcome.InvalidRegex("textRegex", pat, "invalid pattern")
            val a = event.text.orEmpty()
            val b = event.bigText.orEmpty()
            val matched = (a.isNotEmpty() && re.containsMatchIn(a)) ||
                (b.isNotEmpty() && re.containsMatchIn(b))
            if (!matched) {
                return MatchOutcome.NoMatch("textRegex did not match text or bigText")
            }
        }

        // 5. Action label regex — at least one action label must match.
        trigger.actionLabelRegex?.let { pat ->
            val re = compile(pat)
                ?: return MatchOutcome.InvalidRegex("actionLabelRegex", pat, "invalid pattern")
            val any = event.actionLabels.any { re.containsMatchIn(it) }
            if (!any) {
                return MatchOutcome.NoMatch("no action label matched actionLabelRegex")
            }
        }

        return MatchOutcome.Matched
    }
}

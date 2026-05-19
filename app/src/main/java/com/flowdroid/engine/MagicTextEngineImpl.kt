package com.flowdroid.engine

import com.flowdroid.common.flow.MagicTextEngine
import com.flowdroid.common.flow.MagicTextResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [MagicTextEngine] implementation.
 *
 * Why a hand-rolled single-pass tokeniser instead of a regex-based approach:
 *  - We need precise control over escape semantics (`\{`, `\}`) which interact awkwardly with
 *    Kotlin's `Regex` quoting rules.
 *  - We want a strict "no throw" contract: every malformed input becomes a warning, not a crash.
 *    Magic text runs in the engine's hot path (per-action invocation) — partial template failure
 *    must never crash a whole flow.
 *  - Performance: the test suite asserts 1000 expansions in < 50ms. A handwritten StringBuilder
 *    walker is ~10× faster than `Regex.replace` for this trivial grammar.
 *
 * Grammar reminder (see [MagicTextEngine] kdoc):
 *  - `{path}`       → look up `variables[path]`, expand to "" if absent (warning emitted).
 *  - `\{` / `\}`    → literal `{` / `}`.
 *  - `{` with no closing `}` until end-of-string → emit warning, treat the residue as literal.
 *  - Empty `{}` → warning, expands to "".
 */
@Singleton
class MagicTextEngineImpl @Inject constructor() : MagicTextEngine {

    override fun expand(template: String, variables: Map<String, String>): MagicTextResult {
        if (template.isEmpty()) return MagicTextResult("", emptyList())

        val out = StringBuilder(template.length)
        val warnings = mutableListOf<String>()
        var i = 0
        val n = template.length

        while (i < n) {
            val c = template[i]
            when {
                // Escape: \{ or \} → literal { or }. Any other \X is preserved as-is.
                c == '\\' && i + 1 < n && (template[i + 1] == '{' || template[i + 1] == '}') -> {
                    out.append(template[i + 1])
                    i += 2
                }
                c == '{' -> {
                    val closeIdx = findUnescapedClose(template, i + 1)
                    if (closeIdx < 0) {
                        // Unclosed brace: emit warning, append the residue literally and stop.
                        warnings += "unclosed brace at index $i"
                        out.append(template, i, n)
                        i = n
                    } else {
                        val placeholder = template.substring(i + 1, closeIdx)
                        out.append(resolvePlaceholder(placeholder, variables, warnings, i))
                        i = closeIdx + 1
                    }
                }
                c == '}' -> {
                    // A stray `}` is suspicious but tolerated: emit warning and append literally.
                    warnings += "stray '}' at index $i"
                    out.append('}')
                    i++
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }

        return MagicTextResult(out.toString(), warnings)
    }

    /**
     * Resolve one placeholder's content (between `{` and `}`).
     *
     * Grammar (Phase 3):
     *   placeholder := path [ "|" modifier-spec ]*
     *   modifier-spec := name [ ":" args ]
     *
     * - Empty path → warning, returns "".
     * - Unknown path → warning, returns "" (modifiers still run on empty input).
     * - Unknown modifier → warning, modifier is skipped (chain continues with previous value).
     *
     * @param placeholder Content between the braces, e.g. `notification.title|regex:Echo (.+)`.
     * @param variables   Live variable scope.
     * @param warnings    Mutable warning buffer (every malformed token appends here).
     * @param startIndex  Source index for warning messages.
     */
    private fun resolvePlaceholder(
        placeholder: String,
        variables: Map<String, String>,
        warnings: MutableList<String>,
        startIndex: Int,
    ): String {
        val segments = placeholder.split('|')
        val path = segments.first()
        if (path.isEmpty()) {
            warnings += "empty placeholder at index $startIndex"
            return ""
        }
        val raw = variables[path]
        if (raw == null) {
            warnings += "unknown key '$path'"
        }
        var value: String = raw ?: ""
        // Apply modifiers left-to-right.
        for (idx in 1 until segments.size) {
            val spec = segments[idx]
            val colon = spec.indexOf(':')
            val name = if (colon < 0) spec else spec.substring(0, colon)
            val args = if (colon < 0) "" else spec.substring(colon + 1)
            val mod = MODIFIERS[name]
            if (mod == null) {
                warnings += "unknown modifier '$name' at index $startIndex"
                continue
            }
            value = mod(value, args, warnings)
        }
        return value
    }

    /**
     * Find the next `}` at or after [start] that is NOT escaped as `\}`. Returns -1 if none.
     * Backslash-escaped braces inside placeholders are not allowed by the grammar, but we treat
     * `\}` defensively to give users an escape hatch for content that contains a literal `}`.
     */
    private fun findUnescapedClose(s: String, start: Int): Int {
        var j = start
        while (j < s.length) {
            val ch = s[j]
            if (ch == '\\' && j + 1 < s.length && (s[j + 1] == '{' || s[j + 1] == '}')) {
                j += 2
                continue
            }
            if (ch == '}') return j
            // We do NOT allow nested `{` to mean nested placeholders — keep grammar flat.
            j++
        }
        return -1
    }

    /**
     * Registry of magic-text modifiers. Adding a new modifier = one entry. Signature:
     * `(input, rawArgs, warnings) -> output`. The input is whatever the previous modifier (or
     * variable lookup) produced. Output is whatever this modifier produces — empty string on
     * failure (with a warning emitted).
     */
    private companion object {
        private val MODIFIERS: Map<String, (String, String, MutableList<String>) -> String> = mapOf(
            "regex" to ::applyRegex,
            "jsonpath" to ::applyJsonPath,
        )

        /**
         * `{path|regex:PATTERN[,group:N]}` — runs [PATTERN] against the input. Returns the
         * matched substring (default), capture group N if specified, or the empty string if
         * the pattern doesn't match. Malformed patterns emit a warning and return "".
         */
        private fun applyRegex(input: String, args: String, warnings: MutableList<String>): String {
            if (args.isEmpty()) {
                warnings += "regex modifier missing pattern"
                return ""
            }
            // Split args on the LAST occurrence of ",group:" so the pattern itself can contain commas.
            val groupMarker = ",group:"
            val groupIdx = args.lastIndexOf(groupMarker)
            val pattern: String
            val group: Int
            if (groupIdx >= 0) {
                pattern = args.substring(0, groupIdx)
                val parsed = args.substring(groupIdx + groupMarker.length).toIntOrNull()
                if (parsed == null || parsed < 0) {
                    warnings += "regex group must be a non-negative integer"
                    return ""
                }
                group = parsed
            } else {
                pattern = args
                group = -1  // default — see below
            }
            val regex = try {
                Regex(pattern)
            } catch (t: Throwable) {
                if (t is OutOfMemoryError ||
                    t is kotlin.coroutines.cancellation.CancellationException
                ) throw t
                warnings += "regex pattern invalid: ${t.message ?: pattern}"
                return ""
            }
            val match = regex.find(input)
            if (match == null) {
                warnings += "regex pattern did not match"
                return ""
            }
            return when {
                group < 0 ->
                    // Default: capture group 1 if there's at least one, else the whole match.
                    if (match.groupValues.size > 1) match.groupValues[1] else match.value
                group < match.groupValues.size -> match.groupValues[group]
                else -> {
                    warnings += "regex group $group out of range (have ${match.groupValues.size - 1})"
                    ""
                }
            }
        }

        /**
         * `{path|jsonpath:$.a.b[0].c}` — interprets [input] as JSON and returns the value at the
         * given path as a string. Supports dot-walks (`$.foo.bar`) and array indices
         * (`$.foo[0].bar`). Strings are returned without their JSON quoting; numbers/booleans
         * are returned via `toString`. Missing path or non-JSON input → empty + warning.
         */
        private fun applyJsonPath(input: String, args: String, warnings: MutableList<String>): String {
            if (args.isEmpty() || !args.startsWith("$")) {
                warnings += "jsonpath must start with '\$'"
                return ""
            }
            val root: JsonElement = try {
                JSON_PARSER.parseToJsonElement(input)
            } catch (t: Throwable) {
                if (t is OutOfMemoryError ||
                    t is kotlin.coroutines.cancellation.CancellationException
                ) throw t
                warnings += "jsonpath input is not valid JSON"
                return ""
            }
            // Tokenise the path after the leading $.
            //  ".name"   → drill into object by name
            //  "[idx]"   → drill into array
            val tokens = tokenizeJsonPath(args.substring(1))
            var node: JsonElement = root
            for (tok in tokens) {
                node = when (tok) {
                    is JsonPathToken.Field -> {
                        val obj = node as? JsonObject
                        if (obj == null) {
                            warnings += "jsonpath: expected object at ${tok.name}"
                            return ""
                        }
                        obj[tok.name] ?: run {
                            warnings += "jsonpath: key '${tok.name}' not found"
                            return ""
                        }
                    }
                    is JsonPathToken.Index -> {
                        val arr = node as? JsonArray
                        if (arr == null) {
                            warnings += "jsonpath: expected array at index ${tok.index}"
                            return ""
                        }
                        if (tok.index !in arr.indices) {
                            warnings += "jsonpath: index ${tok.index} out of range (size ${arr.size})"
                            return ""
                        }
                        arr[tok.index]
                    }
                }
            }
            return when (node) {
                is JsonNull -> ""
                is JsonPrimitive -> node.content
                else -> node.toString()  // object/array → JSON dump
            }
        }

        private sealed interface JsonPathToken {
            data class Field(val name: String) : JsonPathToken
            data class Index(val index: Int) : JsonPathToken
        }

        private fun tokenizeJsonPath(spec: String): List<JsonPathToken> {
            val tokens = mutableListOf<JsonPathToken>()
            var i = 0
            while (i < spec.length) {
                when (spec[i]) {
                    '.' -> {
                        i++
                        val start = i
                        while (i < spec.length && spec[i] != '.' && spec[i] != '[') i++
                        if (i > start) tokens += JsonPathToken.Field(spec.substring(start, i))
                    }
                    '[' -> {
                        val end = spec.indexOf(']', i + 1)
                        if (end < 0) return tokens // malformed; abort
                        val numStr = spec.substring(i + 1, end)
                        val num = numStr.toIntOrNull() ?: return tokens
                        tokens += JsonPathToken.Index(num)
                        i = end + 1
                    }
                    else -> i++
                }
            }
            return tokens
        }

        private val JSON_PARSER = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

package com.flowdroid.engine

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeout
import java.time.Duration

class MagicTextEngineImplTest {

    private val engine = MagicTextEngineImpl()

    @Test fun `empty template yields empty result with no warnings`() {
        val r = engine.expand("", emptyMap())
        assertThat(r.text).isEmpty()
        assertThat(r.warnings).isEmpty()
    }

    @Test fun `no placeholders is identity`() {
        val r = engine.expand("hello world", mapOf("x" to "y"))
        assertThat(r.text).isEqualTo("hello world")
        assertThat(r.warnings).isEmpty()
    }

    @Test fun `single placeholder expands`() {
        val r = engine.expand("Hi {name}!", mapOf("name" to "Alice"))
        assertThat(r.text).isEqualTo("Hi Alice!")
        assertThat(r.warnings).isEmpty()
    }

    @Test fun `multiple placeholders expand`() {
        val r = engine.expand(
            "{a}+{b}={c}",
            mapOf("a" to "1", "b" to "2", "c" to "3"),
        )
        assertThat(r.text).isEqualTo("1+2=3")
    }

    @Test fun `dotted path is just a key`() {
        val r = engine.expand("{notification.title}", mapOf("notification.title" to "Echo slot"))
        assertThat(r.text).isEqualTo("Echo slot")
    }

    @Test fun `unknown key expands to empty and warns`() {
        val r = engine.expand("X{missing}Y", emptyMap())
        assertThat(r.text).isEqualTo("XY")
        assertThat(r.warnings).hasSize(1)
        assertThat(r.warnings[0]).contains("missing")
    }

    @Test fun `escaped braces render literally`() {
        val r = engine.expand("\\{not a key\\}", mapOf("not a key" to "wrong"))
        assertThat(r.text).isEqualTo("{not a key}")
        assertThat(r.warnings).isEmpty()
    }

    @Test fun `escape sequence inside larger text`() {
        val r = engine.expand("a\\{b}c", mapOf("b" to "B"))
        // \{ → "{", then "b}c" is literal (no opening brace left).
        assertThat(r.text).isEqualTo("a{b}c")
    }

    @Test fun `unclosed brace produces warning and literal residue`() {
        val r = engine.expand("hello {unclosed", emptyMap())
        assertThat(r.text).isEqualTo("hello {unclosed")
        assertThat(r.warnings).hasSize(1)
        assertThat(r.warnings[0]).contains("unclosed")
    }

    @Test fun `stray close brace warns and renders literally`() {
        val r = engine.expand("hello }}", emptyMap())
        assertThat(r.text).isEqualTo("hello }}")
        assertThat(r.warnings).hasSize(2)
    }

    @Test fun `empty placeholder warns`() {
        val r = engine.expand("a{}b", emptyMap())
        assertThat(r.text).isEqualTo("ab")
        assertThat(r.warnings).hasSize(1)
        assertThat(r.warnings[0]).contains("empty")
    }

    @Test fun `value with braces is appended verbatim`() {
        val r = engine.expand("{x}", mapOf("x" to "literal{stuff}"))
        assertThat(r.text).isEqualTo("literal{stuff}")
    }

    @Test fun `1000 expansions complete within budget`() {
        val vars = mapOf(
            "a" to "alpha",
            "b" to "beta",
            "c" to "gamma",
        )
        assertTimeout(Duration.ofMillis(500)) {
            repeat(1000) {
                val r = engine.expand("{a}-{b}-{c} ({a}/{c})", vars)
                assertThat(r.text).isEqualTo("alpha-beta-gamma (alpha/gamma)")
            }
        }
    }

    @Test fun `indexed action label binding`() {
        val r = engine.expand(
            "btn={notification.action[0].label}",
            mapOf("notification.action[0].label" to "Acknowledge"),
        )
        assertThat(r.text).isEqualTo("btn=Acknowledge")
    }

    @Test fun `never throws on malformed input`() {
        // Sanity: a torture string mixing every malformation type — must not throw.
        val r = engine.expand("\\{a}\\}b{c{d}}{}{unclosed", mapOf("c" to "C", "d" to "D"))
        // We don't pin the exact output — just that we got *some* output and warnings.
        assertThat(r.text).isNotNull()
        assertThat(r.warnings).isNotEmpty()
    }

    // ── Regex modifier ─────────────────────────────────────────────────────────

    @Test fun `regex modifier returns default capture group 1`() {
        val r = engine.expand(
            "id={text|regex:matches/(\\d+)/join}",
            mapOf("text" to "see matches/42/join now"),
        )
        assertThat(r.text).isEqualTo("id=42")
    }

    @Test fun `regex modifier with no capture group returns whole match`() {
        val r = engine.expand("{text|regex:\\d+}", mapOf("text" to "abc 99 def"))
        assertThat(r.text).isEqualTo("99")
    }

    @Test fun `regex modifier explicit group selection`() {
        val r = engine.expand(
            "{text|regex:(\\w+)-(\\d+),group:2}",
            mapOf("text" to "match-7"),
        )
        assertThat(r.text).isEqualTo("7")
    }

    @Test fun `regex no match emits warning and expands to empty`() {
        val r = engine.expand("{text|regex:NOPE}", mapOf("text" to "abc"))
        assertThat(r.text).isEmpty()
        assertThat(r.warnings.any { it.contains("did not match") }).isTrue()
    }

    @Test fun `regex with invalid pattern emits warning and expands to empty`() {
        val r = engine.expand("{text|regex:[unclosed}", mapOf("text" to "abc"))
        assertThat(r.text).isEmpty()
        assertThat(r.warnings.any { it.contains("invalid") }).isTrue()
    }

    // ── JsonPath modifier ──────────────────────────────────────────────────────

    @Test fun `jsonpath extracts simple field`() {
        val r = engine.expand(
            "{body|jsonpath:\$.name}",
            mapOf("body" to """{"name":"Alice","age":40}"""),
        )
        assertThat(r.text).isEqualTo("Alice")
    }

    @Test fun `jsonpath extracts array element`() {
        val r = engine.expand(
            "{body|jsonpath:\$.matches[0].id}",
            mapOf("body" to """{"matches":[{"id":42},{"id":99}]}"""),
        )
        assertThat(r.text).isEqualTo("42")
    }

    @Test fun `jsonpath missing path emits warning and expands to empty`() {
        val r = engine.expand(
            "{body|jsonpath:\$.nope}",
            mapOf("body" to """{"name":"Alice"}"""),
        )
        assertThat(r.text).isEmpty()
        assertThat(r.warnings.any { it.contains("not found") }).isTrue()
    }

    @Test fun `jsonpath non-JSON input emits warning and expands to empty`() {
        val r = engine.expand(
            "{body|jsonpath:\$.x}",
            mapOf("body" to "this is not json"),
        )
        assertThat(r.text).isEmpty()
        assertThat(r.warnings.any { it.contains("not valid JSON") }).isTrue()
    }

    @Test fun `jsonpath chained with regex modifier`() {
        // First jsonpath pulls the URL, then regex extracts the path segment.
        val r = engine.expand(
            "{body|jsonpath:\$.url|regex:/api/(.+)\$}",
            mapOf("body" to """{"url":"https://x.test/api/games/42"}"""),
        )
        assertThat(r.text).isEqualTo("games/42")
    }

    @Test fun `unknown modifier is skipped with warning`() {
        val r = engine.expand("{name|nonexistent:args}", mapOf("name" to "Alice"))
        assertThat(r.text).isEqualTo("Alice")
        assertThat(r.warnings.any { it.contains("unknown modifier") }).isTrue()
    }
}

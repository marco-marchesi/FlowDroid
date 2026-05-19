package com.flowdroid.engine

import com.flowdroid.common.TimberStructuredLogger
import com.flowdroid.common.flow.CompareOp
import com.flowdroid.common.flow.Condition
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ConditionEvaluatorTest {

    private val magicText = MagicTextEngineImpl()
    private val logger = TimberStructuredLogger()

    private fun eval(c: Condition, vars: Map<String, String> = emptyMap()): Boolean =
        ConditionEvaluator.evaluate(c, vars, magicText, logger)

    @Test fun `EQUALS literal match`() {
        assertThat(eval(Condition("hi", CompareOp.EQUALS, "hi"))).isTrue()
        assertThat(eval(Condition("hi", CompareOp.EQUALS, "bye"))).isFalse()
    }

    @Test fun `NOT_EQUALS is inverse of EQUALS`() {
        assertThat(eval(Condition("a", CompareOp.NOT_EQUALS, "b"))).isTrue()
        assertThat(eval(Condition("a", CompareOp.NOT_EQUALS, "a"))).isFalse()
    }

    @Test fun `EQUALS expands magic text on both sides`() {
        val vars = mapOf("status" to "200", "expected" to "200")
        assertThat(eval(Condition("{status}", CompareOp.EQUALS, "{expected}"), vars)).isTrue()
    }

    @Test fun `CONTAINS STARTS_WITH ENDS_WITH`() {
        assertThat(eval(Condition("hello world", CompareOp.CONTAINS, "lo wo"))).isTrue()
        assertThat(eval(Condition("hello", CompareOp.STARTS_WITH, "hel"))).isTrue()
        assertThat(eval(Condition("hello", CompareOp.ENDS_WITH, "llo"))).isTrue()
        assertThat(eval(Condition("hello", CompareOp.STARTS_WITH, "world"))).isFalse()
    }

    @Test fun `REGEX matches a partial pattern`() {
        assertThat(eval(Condition("HTTP/200 OK", CompareOp.REGEX, "\\d+"))).isTrue()
        assertThat(eval(Condition("no digits here", CompareOp.REGEX, "\\d+"))).isFalse()
    }

    @Test fun `invalid REGEX returns false without throwing`() {
        // Unclosed group is invalid.
        assertThat(eval(Condition("anything", CompareOp.REGEX, "(unclosed"))).isFalse()
    }

    @Test fun `numeric GT LT GTE LTE`() {
        assertThat(eval(Condition("10", CompareOp.GT, "5"))).isTrue()
        assertThat(eval(Condition("5", CompareOp.GT, "10"))).isFalse()
        assertThat(eval(Condition("5", CompareOp.LT, "10"))).isTrue()
        assertThat(eval(Condition("10", CompareOp.GTE, "10"))).isTrue()
        assertThat(eval(Condition("10", CompareOp.LTE, "10"))).isTrue()
        assertThat(eval(Condition("9.5", CompareOp.LT, "10"))).isTrue()
    }

    @Test fun `numeric op with non-numeric side returns false`() {
        assertThat(eval(Condition("abc", CompareOp.GT, "5"))).isFalse()
        assertThat(eval(Condition("5", CompareOp.GT, "abc"))).isFalse()
    }

    @Test fun `IS_BLANK and IS_NOT_BLANK ignore the right side`() {
        assertThat(eval(Condition("", CompareOp.IS_BLANK))).isTrue()
        assertThat(eval(Condition("   ", CompareOp.IS_BLANK))).isTrue()
        assertThat(eval(Condition("x", CompareOp.IS_BLANK))).isFalse()
        assertThat(eval(Condition("x", CompareOp.IS_NOT_BLANK))).isTrue()
        assertThat(eval(Condition("", CompareOp.IS_NOT_BLANK))).isFalse()
    }

    @Test fun `magic text expanding to missing variable yields empty string`() {
        assertThat(eval(Condition("{var.missing}", CompareOp.IS_BLANK))).isTrue()
    }
}

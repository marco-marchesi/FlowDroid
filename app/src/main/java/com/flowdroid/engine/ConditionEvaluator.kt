package com.flowdroid.engine

import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.flow.CompareOp
import com.flowdroid.common.flow.Condition
import com.flowdroid.common.flow.MagicTextEngine
import com.flowdroid.common.flow.expandOrEmpty

/**
 * Pure-function evaluator for [Condition]s used by [com.flowdroid.engine.actions.IfExecutor].
 *
 * Both sides of the condition are magic-text expanded against [variables] before comparison —
 * this is what lets a user write `Condition("{var.status}", EQUALS, "200")` and have it evaluated
 * after an earlier HTTP action populated `var.status`.
 *
 * Numeric comparisons ([CompareOp.GT], [CompareOp.LT], [CompareOp.GTE], [CompareOp.LTE]) parse
 * BOTH sides as `Double`. If either parse fails the operator returns `false` (and a WARN is
 * logged with the offending side) — this is friendlier than throwing because flows often mix
 * string and number bindings and we'd rather take the false branch than abort the flow.
 *
 * Regex compilation is best-effort: an invalid pattern is treated as a no-match and logged at
 * WARN, again so a typo in a flow doesn't crash the run.
 */
object ConditionEvaluator {

    private const val TAG = "ConditionEvaluator"

    fun evaluate(
        condition: Condition,
        variables: Map<String, String>,
        magicText: MagicTextEngine,
        logger: StructuredLogger,
    ): Boolean {
        val left = magicText.expandOrEmpty(condition.left, variables)
        val right = magicText.expandOrEmpty(condition.right, variables)
        return when (condition.op) {
            CompareOp.EQUALS -> left == right
            CompareOp.NOT_EQUALS -> left != right
            CompareOp.CONTAINS -> left.contains(right)
            CompareOp.STARTS_WITH -> left.startsWith(right)
            CompareOp.ENDS_WITH -> left.endsWith(right)
            CompareOp.REGEX -> matchRegex(left, right, logger)
            CompareOp.GT -> numericCompare(left, right, logger) { a, b -> a > b }
            CompareOp.LT -> numericCompare(left, right, logger) { a, b -> a < b }
            CompareOp.GTE -> numericCompare(left, right, logger) { a, b -> a >= b }
            CompareOp.LTE -> numericCompare(left, right, logger) { a, b -> a <= b }
            CompareOp.IS_BLANK -> left.isBlank()
            CompareOp.IS_NOT_BLANK -> left.isNotBlank()
        }
    }

    private fun matchRegex(left: String, pattern: String, logger: StructuredLogger): Boolean =
        try {
            Regex(pattern).containsMatchIn(left)
        } catch (e: Throwable) {
            if (e is OutOfMemoryError ||
                e is kotlin.coroutines.cancellation.CancellationException
            ) throw e
            logger.warn(TAG, "invalid regex in condition", e, "pattern" to pattern)
            false
        }

    private inline fun numericCompare(
        left: String,
        right: String,
        logger: StructuredLogger,
        cmp: (Double, Double) -> Boolean,
    ): Boolean {
        val l = left.toDoubleOrNull()
        val r = right.toDoubleOrNull()
        if (l == null || r == null) {
            logger.warn(TAG, "non-numeric operand in numeric compare", null,
                "left" to left, "right" to right)
            return false
        }
        return cmp(l, r)
    }
}

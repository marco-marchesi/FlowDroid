package com.flowdroid.engine.actions

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.flowdroid.common.Outcome
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.ActionExecutor
import com.flowdroid.common.flow.ExecutionContext
import com.flowdroid.common.flow.ExecutionError
import com.flowdroid.common.flow.MagicTextEngine
import com.flowdroid.common.flow.expandOrEmpty
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executors for clipboard read/write. Kept in one file because they share the same
 * [ClipboardManager] dependency and trivial implementation surface.
 */
@Singleton
class CopyToClipboardExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val magicText: MagicTextEngine,
    private val logger: StructuredLogger,
) : ActionExecutor<Action.CopyToClipboard> {

    override val actionClass: Class<Action.CopyToClipboard> = Action.CopyToClipboard::class.java

    override suspend fun execute(
        action: Action.CopyToClipboard,
        context: ExecutionContext,
    ): Outcome<Unit, ExecutionError> {
        val text = magicText.expandOrEmpty(action.text, context.variables)
        return try {
            val cm = this.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return Outcome.err(ExecutionError.SystemFailure("no ClipboardManager"))
            // "FlowDroid" label appears in some clipboard-paste UIs; non-sensitive identifier.
            cm.setPrimaryClip(ClipData.newPlainText("FlowDroid", text))
            logger.info(TAG, "clipboard set",
                "flowId" to context.flow.id,
                "execId" to context.executionId,
                "len" to text.length)
            Outcome.ok(Unit)
        } catch (t: Throwable) {
            if (t is OutOfMemoryError ||
                t is kotlin.coroutines.cancellation.CancellationException
            ) throw t
            logger.warn(TAG, "clipboard set threw", t, "flowId" to context.flow.id)
            Outcome.err(ExecutionError.SystemFailure(t.message ?: "clipboard failed"), t)
        }
    }

    companion object { private const val TAG = "Action.CopyToClipboard" }
}

@Singleton
class GetClipboardExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: StructuredLogger,
) : ActionExecutor<Action.GetClipboard> {

    override val actionClass: Class<Action.GetClipboard> = Action.GetClipboard::class.java

    override suspend fun execute(
        action: Action.GetClipboard,
        context: ExecutionContext,
    ): Outcome<Unit, ExecutionError> {
        val varName = action.intoVar.trim()
        if (varName.isEmpty()) {
            return Outcome.err(ExecutionError.InvalidParam("intoVar", "required"))
        }
        return try {
            val cm = this.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return Outcome.err(ExecutionError.SystemFailure("no ClipboardManager"))
            // Android 10+ may return null for the clipboard when not the foreground app — we
            // treat that as empty rather than failure (the value is empty, the action ran).
            val text = cm.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(this.context)?.toString().orEmpty()
            context.variables[varName] = text
            logger.info(TAG, "clipboard read",
                "flowId" to context.flow.id,
                "execId" to context.executionId,
                "intoVar" to varName,
                "len" to text.length)
            Outcome.ok(Unit)
        } catch (t: Throwable) {
            if (t is OutOfMemoryError ||
                t is kotlin.coroutines.cancellation.CancellationException
            ) throw t
            logger.warn(TAG, "clipboard read threw", t, "flowId" to context.flow.id)
            Outcome.err(ExecutionError.SystemFailure(t.message ?: "clipboard read failed"), t)
        }
    }

    companion object { private const val TAG = "Action.GetClipboard" }
}

package com.flowdroid.engine.actions

import android.content.Context
import android.speech.tts.TextToSpeech
import com.flowdroid.common.Outcome
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.ActionExecutor
import com.flowdroid.common.flow.ExecutionContext
import com.flowdroid.common.flow.ExecutionError
import com.flowdroid.common.flow.MagicTextEngine
import com.flowdroid.common.flow.TtsQueueMode
import com.flowdroid.common.flow.expandOrEmpty
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Executor for [Action.Tts]. Speaks text via [TextToSpeech].
 *
 * Lifecycle gotcha: TTS init is asynchronous via a callback. We lazy-init a single, app-scoped
 * engine on the first call and re-use it for the lifetime of the process. Locale is reset per
 * call so flows with different `localeTag` values don't bleed into each other.
 */
@Singleton
class TtsExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val magicText: MagicTextEngine,
    private val logger: StructuredLogger,
) : ActionExecutor<Action.Tts> {

    override val actionClass: Class<Action.Tts> = Action.Tts::class.java

    @Volatile private var tts: TextToSpeech? = null
    private val initLock = Any()

    override suspend fun execute(
        action: Action.Tts,
        context: ExecutionContext,
    ): Outcome<Unit, ExecutionError> {
        val text = magicText.expandOrEmpty(action.text, context.variables)
        if (text.isBlank()) return Outcome.err(ExecutionError.InvalidParam("text", "blank"))
        val engine = ensureInitialised() ?: return Outcome.err(
            ExecutionError.SystemFailure("TTS engine could not initialise"),
        )
        return try {
            // Apply per-call locale if provided; revert to default otherwise.
            val locale = action.localeTag?.takeIf { it.isNotBlank() }?.let { Locale.forLanguageTag(it) }
                ?: Locale.getDefault()
            engine.language = locale
            val queueMode = if (action.queue == TtsQueueMode.FLUSH) TextToSpeech.QUEUE_FLUSH
                else TextToSpeech.QUEUE_ADD
            val rc = engine.speak(text, queueMode, null, "flowdroid-${context.executionId}")
            if (rc == TextToSpeech.ERROR) {
                return Outcome.err(ExecutionError.SystemFailure("TextToSpeech.speak returned ERROR"))
            }
            logger.info(TAG, "tts speak",
                "flowId" to context.flow.id,
                "execId" to context.executionId,
                "len" to text.length,
                "locale" to locale.toLanguageTag(),
                "queue" to action.queue.name)
            Outcome.ok(Unit)
        } catch (t: Throwable) {
            if (t is OutOfMemoryError ||
                t is kotlin.coroutines.cancellation.CancellationException
            ) throw t
            logger.warn(TAG, "tts threw", t, "flowId" to context.flow.id)
            Outcome.err(ExecutionError.SystemFailure(t.message ?: "tts failed"), t)
        }
    }

    private suspend fun ensureInitialised(): TextToSpeech? {
        tts?.let { return it }
        return synchronized(initLock) {
            tts ?: run {
                val ready = CompletableDeferred<Boolean>()
                val engine = TextToSpeech(context) { status ->
                    ready.complete(status == TextToSpeech.SUCCESS)
                }
                // TTS init has no synchronous failure path. We block up to 4s for the callback.
                val ok = kotlinx.coroutines.runBlocking {
                    withTimeoutOrNull(4_000L) { ready.await() } ?: false
                }
                if (!ok) {
                    runCatching { engine.shutdown() }
                    return@run null
                }
                tts = engine
                engine
            }
        }
    }

    companion object {
        private const val TAG = "Action.Tts"
    }
}

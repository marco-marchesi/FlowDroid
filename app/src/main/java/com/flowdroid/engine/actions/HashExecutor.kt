package com.flowdroid.engine.actions

import com.flowdroid.common.Outcome
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.ActionExecutor
import com.flowdroid.common.flow.ExecutionContext
import com.flowdroid.common.flow.ExecutionError
import com.flowdroid.common.flow.HashAlgorithm
import com.flowdroid.common.flow.MagicTextEngine
import com.flowdroid.common.flow.expandOrEmpty
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executor for [Action.Hash]. Computes a hex-encoded lowercase digest of the magic-text-expanded
 * input using one of the supported algorithms (MD5/SHA-1/SHA-256/SHA-512).
 *
 * All four algorithms are part of the Java SE crypto provider since API 1, so `MessageDigest` is
 * always available. Output is lowercase hex (matches `printf … | sha256sum` on a normal terminal).
 */
@Singleton
class HashExecutor @Inject constructor(
    private val magicText: MagicTextEngine,
    private val logger: StructuredLogger,
) : ActionExecutor<Action.Hash> {

    override val actionClass: Class<Action.Hash> = Action.Hash::class.java

    override suspend fun execute(
        action: Action.Hash,
        context: ExecutionContext,
    ): Outcome<Unit, ExecutionError> {
        val resolvedInput = magicText.expandOrEmpty(action.input, context.variables)
        val varName = action.intoVar.trim()
        if (varName.isBlank()) {
            return Outcome.err(ExecutionError.InvalidParam("intoVar", "required"))
        }
        return try {
            val algName = when (action.algorithm) {
                HashAlgorithm.MD5 -> "MD5"
                HashAlgorithm.SHA1 -> "SHA-1"
                HashAlgorithm.SHA256 -> "SHA-256"
                HashAlgorithm.SHA512 -> "SHA-512"
            }
            val digest = MessageDigest.getInstance(algName)
                .digest(resolvedInput.toByteArray(Charsets.UTF_8))
            val hex = digest.joinToString(separator = "") { "%02x".format(it) }
            context.variables[varName] = hex
            logger.info(TAG, "hashed",
                "flowId" to context.flow.id,
                "execId" to context.executionId,
                "algorithm" to action.algorithm.name,
                "intoVar" to varName,
                "inputLen" to resolvedInput.length)
            Outcome.ok(Unit)
        } catch (t: Throwable) {
            if (t is OutOfMemoryError ||
                t is kotlin.coroutines.cancellation.CancellationException
            ) throw t
            logger.warn(TAG, "hash threw", t,
                "flowId" to context.flow.id, "algorithm" to action.algorithm.name)
            Outcome.err(ExecutionError.SystemFailure(t.message ?: "hash failed"), t)
        }
    }

    companion object {
        private const val TAG = "Action.Hash"
    }
}

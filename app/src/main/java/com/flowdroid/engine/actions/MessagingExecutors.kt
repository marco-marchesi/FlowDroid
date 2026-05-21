package com.flowdroid.engine.actions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
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
 * Executors for the four messaging actions. Each follows the same pattern: magic-text-expand
 * the user-facing fields, build an Intent (or use the SmsManager for the one truly headless
 * case), fire it, log and return Outcome.
 *
 * WhatsApp / Telegram / Email INTENTIONALLY require the user to tap Send in the target app —
 * the upstream apps explicitly block fully-automated sending. Only SMS is fully headless, and
 * only when the user has granted the SEND_SMS runtime permission.
 */

@Singleton
class SendSmsExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val magicText: MagicTextEngine,
    private val logger: StructuredLogger,
) : ActionExecutor<Action.SendSms> {

    override val actionClass: Class<Action.SendSms> = Action.SendSms::class.java

    override suspend fun execute(
        action: Action.SendSms,
        context: ExecutionContext,
    ): Outcome<Unit, ExecutionError> {
        val phone = magicText.expandOrEmpty(action.phoneNumber, context.variables).trim()
        val body = magicText.expandOrEmpty(action.body, context.variables)
        if (phone.isEmpty()) return Outcome.err(ExecutionError.InvalidParam("phoneNumber", "empty"))
        if (body.isEmpty()) return Outcome.err(ExecutionError.InvalidParam("body", "empty"))

        // Guard: runtime SEND_SMS permission. If not granted we can't send and the SmsManager
        // call would throw SecurityException with a generic message that's worse for the user.
        val granted = ContextCompat.checkSelfPermission(this.context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            return Outcome.err(
                ExecutionError.SystemFailure(
                    "SEND_SMS permission not granted. Grant via Settings → Apps → FlowDroid → " +
                        "Permissions → SMS.",
                ),
            )
        }

        return try {
            val sms = this.context.getSystemService(SmsManager::class.java)
                ?: @Suppress("DEPRECATION") SmsManager.getDefault()
            val parts = sms.divideMessage(body)
            if (parts.size == 1) {
                sms.sendTextMessage(phone, null, body, null, null)
            } else {
                sms.sendMultipartTextMessage(phone, null, parts, null, null)
            }
            logger.info(TAG, "sms sent",
                "flowId" to context.flow.id,
                "execId" to context.executionId,
                "phoneDigits" to phone.count { it.isDigit() },
                "parts" to parts.size,
                "len" to body.length)
            Outcome.ok(Unit)
        } catch (t: SecurityException) {
            Outcome.err(ExecutionError.SystemFailure("SEND_SMS denied at runtime: ${t.message}"))
        } catch (t: Throwable) {
            if (t is OutOfMemoryError ||
                t is kotlin.coroutines.cancellation.CancellationException
            ) throw t
            logger.warn(TAG, "sms send threw", t, "flowId" to context.flow.id)
            Outcome.err(ExecutionError.SystemFailure(t.message ?: "sms send failed"), t)
        }
    }

    companion object { private const val TAG = "Action.SendSms" }
}

@Singleton
class SendWhatsAppExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val magicText: MagicTextEngine,
    private val logger: StructuredLogger,
) : ActionExecutor<Action.SendWhatsApp> {

    override val actionClass: Class<Action.SendWhatsApp> = Action.SendWhatsApp::class.java

    override suspend fun execute(
        action: Action.SendWhatsApp,
        context: ExecutionContext,
    ): Outcome<Unit, ExecutionError> {
        val phoneRaw = magicText.expandOrEmpty(action.phoneNumber, context.variables).trim()
        val body = magicText.expandOrEmpty(action.body, context.variables)
        if (phoneRaw.isEmpty()) return Outcome.err(ExecutionError.InvalidParam("phoneNumber", "empty"))
        // Strip non-digits because wa.me requires only digits (no +).
        val phone = phoneRaw.filter { it.isDigit() }
        if (phone.isEmpty()) return Outcome.err(ExecutionError.InvalidParam("phoneNumber", "no digits"))

        // Officially supported deep link. WhatsApp opens the chat with the body pre-filled; the
        // user still has to tap the send button (WhatsApp blocks fully-headless sends).
        val uri = Uri.parse("https://wa.me/$phone?text=${Uri.encode(body)}")
        return try {
            this.context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            logger.info(TAG, "whatsapp opened",
                "flowId" to context.flow.id,
                "execId" to context.executionId,
                "phoneDigits" to phone.length,
                "bodyLen" to body.length)
            Outcome.ok(Unit)
        } catch (t: android.content.ActivityNotFoundException) {
            Outcome.err(ExecutionError.TargetNotFound("no app handles wa.me URLs"))
        } catch (t: Throwable) {
            if (t is OutOfMemoryError ||
                t is kotlin.coroutines.cancellation.CancellationException
            ) throw t
            logger.warn(TAG, "whatsapp threw", t, "flowId" to context.flow.id)
            Outcome.err(ExecutionError.SystemFailure(t.message ?: "whatsapp failed"), t)
        }
    }

    companion object { private const val TAG = "Action.SendWhatsApp" }
}

@Singleton
class SendTelegramExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val magicText: MagicTextEngine,
    private val logger: StructuredLogger,
) : ActionExecutor<Action.SendTelegram> {

    override val actionClass: Class<Action.SendTelegram> = Action.SendTelegram::class.java

    override suspend fun execute(
        action: Action.SendTelegram,
        context: ExecutionContext,
    ): Outcome<Unit, ExecutionError> {
        val recipient = magicText.expandOrEmpty(action.recipient, context.variables).trim().trimStart('@')
        val body = magicText.expandOrEmpty(action.body, context.variables)
        if (recipient.isEmpty()) return Outcome.err(ExecutionError.InvalidParam("recipient", "empty"))

        // Two URL forms: tg://resolve?domain=… for usernames, tg://msg?text=…&to=… for phones.
        // We prefer the universal `https://t.me/<recipient>?text=…` form which Telegram catches
        // for both usernames and phone numbers — fewer special cases.
        val uri = Uri.parse("https://t.me/$recipient?text=${Uri.encode(body)}")
        return try {
            this.context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            logger.info(TAG, "telegram opened",
                "flowId" to context.flow.id,
                "execId" to context.executionId,
                "recipientLen" to recipient.length,
                "bodyLen" to body.length)
            Outcome.ok(Unit)
        } catch (t: android.content.ActivityNotFoundException) {
            Outcome.err(ExecutionError.TargetNotFound("Telegram not installed"))
        } catch (t: Throwable) {
            if (t is OutOfMemoryError ||
                t is kotlin.coroutines.cancellation.CancellationException
            ) throw t
            logger.warn(TAG, "telegram threw", t, "flowId" to context.flow.id)
            Outcome.err(ExecutionError.SystemFailure(t.message ?: "telegram failed"), t)
        }
    }

    companion object { private const val TAG = "Action.SendTelegram" }
}

@Singleton
class SendEmailExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val magicText: MagicTextEngine,
    private val logger: StructuredLogger,
) : ActionExecutor<Action.SendEmail> {

    override val actionClass: Class<Action.SendEmail> = Action.SendEmail::class.java

    override suspend fun execute(
        action: Action.SendEmail,
        context: ExecutionContext,
    ): Outcome<Unit, ExecutionError> {
        val to = magicText.expandOrEmpty(action.to, context.variables).trim()
        if (to.isEmpty()) return Outcome.err(ExecutionError.InvalidParam("to", "empty"))
        val subject = magicText.expandOrEmpty(action.subject, context.variables)
        val body = magicText.expandOrEmpty(action.body, context.variables)
        val cc = magicText.expandOrEmpty(action.cc, context.variables).trim()
        val bcc = magicText.expandOrEmpty(action.bcc, context.variables).trim()

        // mailto: query params. We build the URI string manually because Uri.Builder URL-encodes
        // everything including the @-sign in the path, which some email apps then refuse to parse.
        val query = buildList {
            if (subject.isNotEmpty()) add("subject=${Uri.encode(subject)}")
            if (body.isNotEmpty()) add("body=${Uri.encode(body)}")
            if (cc.isNotEmpty()) add("cc=${Uri.encode(cc)}")
            if (bcc.isNotEmpty()) add("bcc=${Uri.encode(bcc)}")
        }.joinToString("&")
        val mailto = "mailto:${Uri.encode(to)}" + if (query.isEmpty()) "" else "?$query"

        return try {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(mailto))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            this.context.startActivity(intent)
            logger.info(TAG, "email composer opened",
                "flowId" to context.flow.id,
                "execId" to context.executionId,
                "subjectLen" to subject.length,
                "bodyLen" to body.length,
                "hasCc" to cc.isNotEmpty(),
                "hasBcc" to bcc.isNotEmpty())
            Outcome.ok(Unit)
        } catch (t: android.content.ActivityNotFoundException) {
            Outcome.err(ExecutionError.TargetNotFound("no email app installed"))
        } catch (t: Throwable) {
            if (t is OutOfMemoryError ||
                t is kotlin.coroutines.cancellation.CancellationException
            ) throw t
            logger.warn(TAG, "email threw", t, "flowId" to context.flow.id)
            Outcome.err(ExecutionError.SystemFailure(t.message ?: "email failed"), t)
        }
    }

    companion object { private const val TAG = "Action.SendEmail" }
}

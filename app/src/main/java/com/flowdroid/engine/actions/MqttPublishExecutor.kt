package com.flowdroid.engine.actions

import com.flowdroid.common.Outcome
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.ActionExecutor
import com.flowdroid.common.flow.ExecutionContext
import com.flowdroid.common.flow.ExecutionError
import com.flowdroid.common.flow.MagicTextEngine
import com.flowdroid.common.flow.expandOrEmpty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLSocketFactory

/**
 * Executes [Action.MqttPublish] — connects to the specified broker, publishes a single message,
 * then disconnects. One-shot per execution; not pooled (pool would need expiry logic that isn't
 * worth the complexity here — a typical publish action runs every few seconds at most).
 *
 * Logging: credentials are never written to logs. Payload is logged at DEBUG level (truncated).
 */
@Singleton
class MqttPublishExecutor @Inject constructor(
    private val magicText: MagicTextEngine,
    private val logger: StructuredLogger,
) : ActionExecutor<Action.MqttPublish> {

    override val actionClass: Class<Action.MqttPublish> = Action.MqttPublish::class.java

    override suspend fun execute(
        action: Action.MqttPublish,
        context: ExecutionContext,
    ): Outcome<Unit, ExecutionError> = withContext(Dispatchers.IO) {
        val brokerUrl = magicText.expandOrEmpty(action.brokerUrl, context.variables)
        val topic     = magicText.expandOrEmpty(action.topic, context.variables)
        val payload   = magicText.expandOrEmpty(action.payload, context.variables)
        val username  = magicText.expandOrEmpty(action.username, context.variables)
        val password  = magicText.expandOrEmpty(action.password, context.variables)
        val clientId  = action.clientId.ifBlank { MqttClient.generateClientId() }

        if (brokerUrl.isBlank()) {
            return@withContext Outcome.Err(ExecutionError.InvalidParam("brokerUrl", "must not be blank"))
        }
        if (topic.isBlank()) {
            return@withContext Outcome.Err(ExecutionError.InvalidParam("topic", "must not be blank"))
        }

        logger.debug(TAG, "publish start",
            "broker" to brokerUrl, "topic" to topic,
            "qos" to action.qos, "retained" to action.retained,
            "payloadLen" to payload.length)

        val result = withTimeoutOrNull(action.timeoutMs) {
            runCatching {
                val client = MqttClient(brokerUrl, clientId, MemoryPersistence())
                try {
                    val opts = MqttConnectOptions().apply {
                        isCleanSession = true
                        connectionTimeout = ((action.timeoutMs / 1000).coerceAtLeast(1)).toInt()
                        if (username.isNotBlank()) {
                            this.userName = username
                            this.password = password.toCharArray()
                        }
                        if (brokerUrl.startsWith("ssl://", ignoreCase = true) ||
                            brokerUrl.startsWith("wss://", ignoreCase = true)
                        ) {
                            socketFactory = SSLSocketFactory.getDefault()
                        }
                    }
                    client.connect(opts)
                    val msg = MqttMessage(payload.toByteArray(Charsets.UTF_8)).apply {
                        qos = action.qos.coerceIn(0, 2)
                        isRetained = action.retained
                    }
                    client.publish(topic, msg)
                    logger.info(TAG, "published",
                        "broker" to brokerUrl, "topic" to topic, "qos" to action.qos)
                } finally {
                    runCatching { if (client.isConnected) client.disconnect() }
                    runCatching { client.close() }
                }
            }
        }

        when {
            result == null -> {
                logger.warn(TAG, "timeout", null, "broker" to brokerUrl, "timeoutMs" to action.timeoutMs)
                Outcome.Err(ExecutionError.Timeout(action.timeoutMs))
            }
            result.isFailure -> {
                val cause = result.exceptionOrNull()
                logger.warn(TAG, "publish failed", cause, "broker" to brokerUrl)
                Outcome.Err(ExecutionError.SystemFailure(
                    cause?.let { mqttErrorMessage(it) } ?: "unknown error",
                ))
            }
            else -> {
                if (action.storeResponseInVar.isNotBlank()) {
                    context.variables[action.storeResponseInVar] = "ok"
                }
                Outcome.Ok(Unit)
            }
        }
    }

    private fun mqttErrorMessage(t: Throwable): String =
        if (t is MqttException) "MqttException(${t.reasonCode}): ${t.message}"
        else t.message ?: t.javaClass.simpleName

    companion object {
        private const val TAG = "MqttPublishExecutor"
    }
}

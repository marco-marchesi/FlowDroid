package com.flowdroid.platform.mqtt

import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.flow.FlowEngine
import com.flowdroid.common.flow.Trigger
import com.flowdroid.common.flow.TriggerEvent
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLSocketFactory

/**
 * Manages persistent MQTT subscriptions for [Trigger.MqttSubscribe] triggers.
 *
 * Connection keying:
 *   One [MqttClient] per (brokerUrl, username) pair — different credentials on the same host
 *   each get their own connection. Stored in [connections] under [connectionKey].
 *
 * SSL:
 *   Any broker URL starting with "ssl://" or "wss://" gets an explicit [SSLSocketFactory] from
 *   the Android system trust store. This supports public-CA brokers (HiveMQ Cloud uses
 *   Let's Encrypt, trusted on Android 7+).
 *
 * Reconnection:
 *   [MqttConnectOptions.isAutomaticReconnect] handles TCP-level reconnect. We use
 *   [MqttCallbackExtended.connectComplete] (not [MqttCallbackExtended.connectionLost]) to
 *   re-subscribe topics after Paho restores the session — subscriptions are NOT restored
 *   automatically by the broker when using cleanSession=true.
 *
 * Clean session:
 *   Always true. Paho's MemoryPersistence loses state on restart anyway, so
 *   cleanSession=false would cause QoS 1/2 protocol errors after restart.
 *   The trade-off: messages published while the device is offline are not re-delivered.
 *   For an automation trigger that is acceptable.
 */
@Singleton
class MqttSubscribeManager @Inject constructor(
    private val flowEngine: FlowEngine,
    private val logger: StructuredLogger,
) {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("MqttSubscribeManager"),
    )

    /** flowId -> subscription config */
    private val subscriptions = ConcurrentHashMap<String, Entry>()

    /** connectionKey -> active Paho client */
    private val connections = ConcurrentHashMap<String, MqttClient>()

    /** Arm a subscription for a flow. Idempotent — calling again replaces the old entry. */
    fun register(flowId: String, trigger: Trigger.MqttSubscribe) {
        subscriptions[flowId] = Entry(flowId, trigger)
        ensureConnected(trigger)
        logger.info(TAG, "registered",
            "flowId" to flowId, "broker" to trigger.brokerUrl, "topic" to trigger.topic)
    }

    /** Disarm a subscription. Tears down the broker connection if no other flows need it. */
    fun unregister(flowId: String) {
        val entry = subscriptions.remove(flowId) ?: return
        logger.info(TAG, "unregistered", "flowId" to flowId)

        val key = connectionKey(entry.trigger)
        val stillNeeded = subscriptions.values.any { connectionKey(it.trigger) == key }
        if (!stillNeeded) {
            scope.launch { tearDown(key) }
        } else {
            val topicStillNeeded = subscriptions.values.any {
                connectionKey(it.trigger) == key && it.trigger.topic == entry.trigger.topic
            }
            if (!topicStillNeeded) {
                connections[key]?.let { client ->
                    runCatching { if (client.isConnected) client.unsubscribe(entry.trigger.topic) }
                }
            }
        }
    }

    private fun ensureConnected(trigger: Trigger.MqttSubscribe) {
        scope.launch {
            val key = connectionKey(trigger)
            try {
                val existing = connections[key]
                val client = if (existing == null) {
                    val c = buildClient(key, trigger)
                    connections[key] = c
                    c
                } else {
                    existing
                }
                if (!client.isConnected) {
                    connectClient(client, trigger)
                }
                subscribeTopic(client, trigger)
            } catch (t: Throwable) {
                if (t is OutOfMemoryError || t is kotlin.coroutines.cancellation.CancellationException) throw t
                logger.warn(TAG, "connect/subscribe failed — ${mqttErrorMessage(t)}",
                    t, "broker" to trigger.brokerUrl)
                scheduleRetry(trigger)
            }
        }
    }

    private fun buildClient(key: String, trigger: Trigger.MqttSubscribe): MqttClient {
        val clientId = trigger.clientId.ifBlank { MqttClient.generateClientId() }
        val client = MqttClient(trigger.brokerUrl, clientId, MemoryPersistence())

        client.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                // Called by Paho AFTER the connection (re-)established, including automatic reconnects.
                // This is the correct place to restore subscriptions — connectionLost fires BEFORE
                // Paho has reconnected, so subscribing there always fails.
                logger.info(TAG, if (reconnect) "reconnected" else "connected",
                    "broker" to trigger.brokerUrl)
                scope.launch { resubscribeAll(key) }
            }

            override fun connectionLost(cause: Throwable?) {
                // Paho's automaticReconnect will try to restore the TCP session.
                // We just log here; re-subscribe happens in connectComplete above.
                logger.warn(TAG, "connection lost — will retry automatically",
                    cause, "broker" to trigger.brokerUrl)
            }

            override fun messageArrived(topic: String, message: MqttMessage) {
                val payload = message.payload?.toString(Charsets.UTF_8).orEmpty()
                logger.debug(TAG, "message arrived",
                    "broker" to trigger.brokerUrl, "topic" to topic, "bytes" to (message.payload?.size ?: 0))
                dispatch(key, topic, payload, message.qos)
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) { /* publish-side, ignore */ }
        })
        return client
    }

    private fun connectClient(client: MqttClient, trigger: Trigger.MqttSubscribe) {
        val opts = MqttConnectOptions().apply {
            isCleanSession = true
            connectionTimeout = 30
            keepAliveInterval = 60
            isAutomaticReconnect = true

            if (trigger.username.isNotBlank()) {
                userName = trigger.username
                password = trigger.password.toCharArray()
            }

            // Explicit SSL socket factory for ssl:// and wss:// brokers (e.g. HiveMQ Cloud).
            // Uses the Android system trust store — public CAs (Let's Encrypt etc.) are trusted.
            if (trigger.brokerUrl.startsWith("ssl://", ignoreCase = true) ||
                trigger.brokerUrl.startsWith("wss://", ignoreCase = true)
            ) {
                socketFactory = SSLSocketFactory.getDefault()
            }
        }
        client.connect(opts)
    }

    private fun subscribeTopic(client: MqttClient, trigger: Trigger.MqttSubscribe) {
        if (!client.isConnected) return
        runCatching {
            client.subscribe(trigger.topic, trigger.qos.coerceIn(0, 2))
            logger.debug(TAG, "subscribed",
                "broker" to trigger.brokerUrl, "topic" to trigger.topic, "qos" to trigger.qos)
        }.onFailure { t ->
            logger.warn(TAG, "subscribe failed", t,
                "broker" to trigger.brokerUrl, "topic" to trigger.topic)
        }
    }

    private fun dispatch(key: String, topic: String, payload: String, qos: Int) {
        val matching = subscriptions.values.filter { entry ->
            connectionKey(entry.trigger) == key &&
                mqttTopicMatches(entry.trigger.topic, topic) &&
                (entry.trigger.payloadRegex.isBlank() ||
                    runCatching { Regex(entry.trigger.payloadRegex).containsMatchIn(payload) }
                        .getOrDefault(false))
        }
        if (matching.isEmpty()) return

        runBlocking(Dispatchers.IO) {
            for (entry in matching) {
                try {
                    flowEngine.onTriggered(
                        entry.flowId,
                        TriggerEvent.MqttMessage(topic = topic, payload = payload, qos = qos),
                    )
                } catch (t: Throwable) {
                    if (t is OutOfMemoryError || t is kotlin.coroutines.cancellation.CancellationException) throw t
                    logger.warn(TAG, "onTriggered failed", t, "flowId" to entry.flowId)
                }
            }
        }
    }

    private suspend fun resubscribeAll(key: String) {
        val client = connections[key] ?: return
        if (!client.isConnected) return
        subscriptions.values
            .filter { connectionKey(it.trigger) == key }
            .groupBy { it.trigger.topic }
            .forEach { (topic, entries) ->
                runCatching {
                    val maxQos = entries.maxOf { it.trigger.qos }.coerceIn(0, 2)
                    client.subscribe(topic, maxQos)
                    logger.debug(TAG, "re-subscribed",
                        "broker" to entries.first().trigger.brokerUrl, "topic" to topic)
                }.onFailure { t ->
                    logger.warn(TAG, "re-subscribe failed", t, "topic" to topic)
                }
            }
    }

    private fun scheduleRetry(trigger: Trigger.MqttSubscribe) {
        scope.launch {
            delay(20_000L)
            val key = connectionKey(trigger)
            if (subscriptions.values.any { connectionKey(it.trigger) == key }) {
                ensureConnected(trigger)
            }
        }
    }

    private suspend fun tearDown(key: String) {
        val client = connections.remove(key) ?: return
        runCatching {
            if (client.isConnected) client.disconnectForcibly(0L, 1_000L)
            client.close(true)
        }
        logger.info(TAG, "connection torn down", "key" to key)
    }

    private data class Entry(val flowId: String, val trigger: Trigger.MqttSubscribe)

    companion object {
        private const val TAG = "MqttSubscribeManager"

        /** Unique key per (broker, username) pair — different credentials stay separate. */
        internal fun connectionKey(trigger: Trigger.MqttSubscribe): String =
            "${trigger.brokerUrl} ${trigger.username}"

        internal fun mqttErrorMessage(t: Throwable): String {
            if (t !is MqttException) return t.message ?: t.javaClass.simpleName
            return when (t.reasonCode.toInt()) {
                MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt() ->
                    "Bad username or password (code 4)"
                MqttException.REASON_CODE_NOT_AUTHORIZED.toInt() ->
                    "Not authorized (code 5) — check credentials and ACL"
                MqttException.REASON_CODE_BROKER_UNAVAILABLE.toInt() ->
                    "Broker unavailable (code 3)"
                MqttException.REASON_CODE_INVALID_CLIENT_ID.toInt() ->
                    "Invalid client ID (code 2) — try leaving Client ID blank"
                MqttException.REASON_CODE_CONNECTION_LOST.toInt() ->
                    "Connection lost (code 32109)"
                MqttException.REASON_CODE_SERVER_CONNECT_ERROR.toInt() ->
                    "Cannot reach broker — check URL/port and network"
                MqttException.REASON_CODE_SSL_CONFIG_ERROR.toInt() ->
                    "SSL configuration error — verify broker URL uses ssl:// for TLS"
                else -> "MqttException(${t.reasonCode}): ${t.message}"
            }
        }

        /**
         * MQTT topic wildcard match.
         *  - `+` matches exactly one level segment.
         *  - `#` matches zero or more levels and must be the last segment.
         */
        fun mqttTopicMatches(pattern: String, topic: String): Boolean {
            if (pattern == "#") return true
            if (pattern == topic) return true
            val patParts = pattern.split('/')
            val topParts = topic.split('/')
            var pi = 0
            var ti = 0
            while (pi < patParts.size && ti < topParts.size) {
                val p = patParts[pi]
                if (p == "#") return true
                if (p != "+" && p != topParts[ti]) return false
                pi++; ti++
            }
            return pi == patParts.size && ti == topParts.size
        }
    }
}

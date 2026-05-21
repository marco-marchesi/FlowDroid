package com.flowdroid.ui.flows

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.Trigger
import com.flowdroid.data.settings.MqttProfile

/** Provides the saved MQTT connection profile from [ConnectionSettingsRepository] to MQTT field composables. */
val LocalSavedMqttProfile = compositionLocalOf { MqttProfile() }

@Composable
fun MqttPublishFields(action: Action.MqttPublish, onUpdate: (Action.MqttPublish) -> Unit) {
    val saved = LocalSavedMqttProfile.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (saved.brokerUrl.isNotBlank()) {
            AssistChip(
                onClick = {
                    onUpdate(action.copy(
                        brokerUrl = saved.brokerUrl,
                        username  = saved.username,
                        password  = saved.password,
                        clientId  = saved.clientId,
                    ))
                },
                label = { Text("Apply saved connection") },
            )
        }
        OutlinedTextField(
            value = action.brokerUrl,
            onValueChange = { onUpdate(action.copy(brokerUrl = it)) },
            label = { Text("Broker URL") },
            placeholder = { Text("tcp://192.168.1.10:1883  or  ssl://….hivemq.cloud:8883") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = action.topic,
            onValueChange = { onUpdate(action.copy(topic = it)) },
            label = { Text("Topic") },
            placeholder = { Text("home/lights/kitchen") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = action.payload,
            onValueChange = { onUpdate(action.copy(payload = it)) },
            label = { Text("Payload  ({mqtt.payload}, {var}, plain text…)") },
            placeholder = { Text("ON") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 4,
        )
        MqttQosDropdown(
            qos = action.qos,
            onQosChange = { onUpdate(action.copy(qos = it)) },
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Checkbox(
                checked = action.retained,
                onCheckedChange = { onUpdate(action.copy(retained = it)) },
            )
            Text("Retain message on broker")
        }
        OutlinedTextField(
            value = action.username,
            onValueChange = { onUpdate(action.copy(username = it)) },
            label = { Text("Username (optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = action.password,
            onValueChange = { onUpdate(action.copy(password = it)) },
            label = { Text("Password (optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        OutlinedTextField(
            value = action.storeResponseInVar.orEmpty(),
            onValueChange = { onUpdate(action.copy(storeResponseInVar = it)) },
            label = { Text("Store result in var (optional)") },
            placeholder = { Text("mqtt_result") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
    }
}

@Composable
fun MqttSubscribeFields(trigger: Trigger.MqttSubscribe, onUpdate: (Trigger.MqttSubscribe) -> Unit) {
    val saved = LocalSavedMqttProfile.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (saved.brokerUrl.isNotBlank()) {
            AssistChip(
                onClick = {
                    onUpdate(trigger.copy(
                        brokerUrl = saved.brokerUrl,
                        username  = saved.username,
                        password  = saved.password,
                        clientId  = saved.clientId,
                    ))
                },
                label = { Text("Apply saved connection") },
            )
        }
        OutlinedTextField(
            value = trigger.brokerUrl,
            onValueChange = { onUpdate(trigger.copy(brokerUrl = it)) },
            label = { Text("Broker URL") },
            placeholder = { Text("tcp://192.168.1.10:1883  or  ssl://….hivemq.cloud:8883") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = trigger.topic,
            onValueChange = { onUpdate(trigger.copy(topic = it)) },
            label = { Text("Topic (wildcards: + or #)") },
            placeholder = { Text("home/sensors/#") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        MqttQosDropdown(
            qos = trigger.qos,
            onQosChange = { onUpdate(trigger.copy(qos = it)) },
        )
        OutlinedTextField(
            value = trigger.payloadRegex,
            onValueChange = { onUpdate(trigger.copy(payloadRegex = it)) },
            label = { Text("Payload filter regex (optional)") },
            placeholder = { Text("^ON$") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = trigger.username,
            onValueChange = { onUpdate(trigger.copy(username = it)) },
            label = { Text("Username (optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = trigger.password,
            onValueChange = { onUpdate(trigger.copy(password = it)) },
            label = { Text("Password (optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
    }
}

@Composable
private fun MqttQosDropdown(qos: Int, onQosChange: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val labels = mapOf(0 to "QoS 0 — At most once", 1 to "QoS 1 — At least once", 2 to "QoS 2 — Exactly once")
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = labels[qos] ?: "QoS $qos",
            onValueChange = {},
            readOnly = true,
            label = { Text("QoS") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            labels.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { onQosChange(value); expanded = false },
                )
            }
        }
    }
}

package com.flowdroid.data.settings

import kotlinx.coroutines.flow.Flow

interface ConnectionSettingsRepository {
    val mqttProfile: Flow<MqttProfile>
    suspend fun saveMqttProfile(profile: MqttProfile)
}

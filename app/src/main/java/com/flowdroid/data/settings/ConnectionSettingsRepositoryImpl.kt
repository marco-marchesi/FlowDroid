package com.flowdroid.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionSettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : ConnectionSettingsRepository {

    override val mqttProfile: Flow<MqttProfile> = dataStore.data.map { prefs ->
        MqttProfile(
            brokerUrl = prefs[MQTT_BROKER_URL] ?: "",
            username  = prefs[MQTT_USERNAME]   ?: "",
            password  = prefs[MQTT_PASSWORD]   ?: "",
            clientId  = prefs[MQTT_CLIENT_ID]  ?: "",
        )
    }

    override suspend fun saveMqttProfile(profile: MqttProfile) {
        dataStore.edit { prefs ->
            prefs[MQTT_BROKER_URL] = profile.brokerUrl
            prefs[MQTT_USERNAME]   = profile.username
            prefs[MQTT_PASSWORD]   = profile.password
            prefs[MQTT_CLIENT_ID]  = profile.clientId
        }
    }

    companion object {
        private val MQTT_BROKER_URL = stringPreferencesKey("mqtt_broker_url")
        private val MQTT_USERNAME   = stringPreferencesKey("mqtt_username")
        private val MQTT_PASSWORD   = stringPreferencesKey("mqtt_password")
        private val MQTT_CLIENT_ID  = stringPreferencesKey("mqtt_client_id")
    }
}

package com.flowdroid.data.settings

data class MqttProfile(
    val brokerUrl: String = "",
    val username: String = "",
    val password: String = "",
    val clientId: String = "",
)

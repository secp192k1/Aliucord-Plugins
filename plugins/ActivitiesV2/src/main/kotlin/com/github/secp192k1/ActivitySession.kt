package com.github.secp192k1

internal data class ActivitySession(
    val applicationId: String,
    val instanceId: String,
    val rawInstanceId: String,
    val launchId: String,
    val channelId: String,
    val guildId: String?,
    val locationId: String,
)

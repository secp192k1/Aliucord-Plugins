package com.github.secp192k1

import com.discord.api.channel.Channel

internal data class ActivitySession(
    val applicationId: String,
    val instanceId: String,
    val rawInstanceId: String,
    val launchId: String,
    val channel: Channel,
    val locationId: String,
)

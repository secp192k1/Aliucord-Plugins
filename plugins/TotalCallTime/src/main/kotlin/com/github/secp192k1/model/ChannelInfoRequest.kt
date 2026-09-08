package com.github.secp192k1.model

import com.aliucord.utils.SerializedName

// Sent as Opcode 43 REQUEST_CHANNEL_INFO, answered with a CHANNEL_INFO dispatch
internal data class ChannelInfoRequest(
    @SerializedName("guild_id") val guildId: Long,
    @SerializedName("fields") val fields: List<String>,
)

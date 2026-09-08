package com.github.secp192k1.model

import com.aliucord.utils.SerializedName

internal data class VoiceStartTime(
    // The channel arrives as "id", not "channel_id" (ragebait)
    @SerializedName("id") val id: Long?,
    @SerializedName("guild_id") val guildId: Long?,
    @SerializedName("voice_start_time") val voiceStartTime: Long?,
)

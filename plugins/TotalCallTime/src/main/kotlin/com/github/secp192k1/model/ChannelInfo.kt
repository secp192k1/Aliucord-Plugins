package com.github.secp192k1.model

import com.aliucord.utils.SerializedName

internal data class ChannelInfo(
    @SerializedName("guild_id") val guildId: Long?,
    @SerializedName("channels") val channels: List<Entry>?,
) {
    internal data class Entry(
        @SerializedName("id") val id: Long?,
        @SerializedName("status") val status: String?,
        // Unix seconds, same as VOICE_CHANNEL_START_TIME_UPDATE
        @SerializedName("voice_start_time") val voiceStartTime: Long?,
    )
}

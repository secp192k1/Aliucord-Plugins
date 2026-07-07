package com.github.secp192k1

import com.aliucord.wrappers.ChannelWrapper
import com.discord.stores.StoreStream
import org.json.JSONArray
import org.json.JSONObject

internal object ActivityData {
    fun channel(session: ActivitySession): JSONObject {
        val json = JSONObject()
            .put("id", session.channelId)
            .put("type", ChannelType.GUILD_TEXT.value)
            .put("name", JSONObject.NULL)
            .put("voice_states", JSONArray())
            .put("messages", JSONArray())

        try {
            val channelId = session.channelId.toLongOrNull() ?: return json
            val channel = StoreStream.getChannels().getChannel(channelId) ?: return json
            val wrapper = ChannelWrapper(channel)
            json.put("type", wrapper.type).put("name", wrapper.name)
        } catch (_: Throwable) { }

        return json
    }

    fun permissions(session: ActivitySession): JSONObject {
        var permissions = 0L

        try {
            session.channelId.toLongOrNull()?.let { channelId ->
                StoreStream.getPermissions().permissionsByChannel[channelId]?.let { permissions = it }
            }
        } catch (_: Throwable) { }

        return JSONObject().put("permissions", permissions.toString())
    }
}

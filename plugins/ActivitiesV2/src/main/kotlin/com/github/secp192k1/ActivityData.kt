package com.github.secp192k1

import com.aliucord.wrappers.ChannelWrapper.Companion.id
import com.aliucord.wrappers.ChannelWrapper.Companion.type
import com.aliucord.wrappers.ChannelWrapper.Companion.name
import com.discord.stores.StoreStream
import org.json.JSONArray
import org.json.JSONObject

internal object ActivityData {
    fun channel(session: ActivitySession): JSONObject {
        val channel = session.channel
        val json = JSONObject()
            .put("id", channel.id)
            .put("type", channel.type)
            .put("name", channel.name)
            .put("voice_states", JSONArray())
            .put("messages", JSONArray())

        return json
    }

    fun permissions(session: ActivitySession): JSONObject {
        var permissions = 0L

        try {
            StoreStream.getPermissions().permissionsByChannel[session.channel.id]?.let { permissions = it }
        } catch (_: Throwable) { }

        return JSONObject().put("permissions", permissions.toString())
    }
}

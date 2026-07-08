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
            .put("id", channel.id.toString())
            .put("type", channel.type)
            .put("name", channel.name)
            .put("voice_states", JSONArray())
            .put("messages", JSONArray())

        return json
    }

    fun participants(userIds: List<Long>): JSONObject {
        val list = JSONArray()
        val users = StoreStream.getUsers().users

        for (id in userIds) {
            val user = users[id] ?: continue

            list.put(
                JSONObject()
                    .put("id", user.id.toString())
                    .put("username", user.username)
                    .put("discriminator", user.discriminator.toString())
                    .put("avatar", user.avatar ?: JSONObject.NULL)
                    .put("flags", 0)
            )
        }

        return JSONObject().put("participants", list)
    }

    fun permissions(session: ActivitySession): JSONObject {
        var permissions = 0L

        try {
            StoreStream.getPermissions().permissionsByChannel[session.channel.id]?.let { permissions = it }
        } catch (_: Throwable) { }

        return JSONObject().put("permissions", permissions.toString())
    }
}

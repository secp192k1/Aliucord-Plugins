package com.github.secp192k1

import com.aliucord.Logger
import com.aliucord.utils.ReflectUtils
import com.aliucord.wrappers.ChannelWrapper.Companion.guildId
import com.aliucord.wrappers.ChannelWrapper.Companion.id
import com.aliucord.wrappers.ChannelWrapper.Companion.type
import com.aliucord.wrappers.ChannelWrapper.Companion.name
import com.discord.api.channel.Channel
import com.discord.api.voice.state.VoiceState
import com.discord.models.user.User
import com.discord.stores.StoreStream
import org.json.JSONArray
import org.json.JSONObject

internal object ActivityData {
    private val logger = Logger("ActivitiesV2")

    fun channel(session: ActivitySession, args: JSONObject): JSONObject {
        val channel = resolveChannel(session, args)
        val json = JSONObject()
            .put("id", channel.id.toString())
            .put("type", channel.type)
            .put("name", channel.name)
            .put("voice_states", voiceStates(channel))
            .put("messages", JSONArray())

        return json
    }

    private fun resolveChannel(session: ActivitySession, args: JSONObject): Channel {
        val requestedId = args.optString("channel_id").toLongOrNull() ?: return session.channel
        if (requestedId == session.channel.id) return session.channel
        return StoreStream.getChannels().getChannel(requestedId) ?: session.channel
    }

    fun participants(userIds: List<Long>): JSONObject {
        val list = JSONArray()
        val users = StoreStream.getUsers().users

        for (id in userIds) {
            val user = users[id] ?: continue
            list.put(userJson(user).put("flags", 0))
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

    private fun voiceStates(channel: Channel): JSONArray {
        val states = JSONArray()

        try {
            val users = StoreStream.getUsers().users
            val voiceStates = StoreStream.getVoiceStates()
                .getForChannel(channel.guildId, channel.id)

            for ((userId, state) in voiceStates) {
                val user = users[userId] ?: continue
                val mute = state.getField("mute")

                states.put(
                    JSONObject()
                        .put("mute", mute)
                        .put("nick", user.username)
                        .put("user", userJson(user))
                        .put("voice_state", JSONObject()
                            .put("mute", mute)
                            .put("deaf", state.getField("deaf"))
                            .put("self_mute", state.getField("selfMute"))
                            .put("self_deaf", state.getField("selfDeaf"))
                            .put("suppress", state.getField("suppress")))
                        .put("volume", 100)
                )
            }
        } catch (e: Throwable) {
            logger.error("Failed to build voice states", e)
        }

        return states
    }

    private fun userJson(user: User) = JSONObject()
        .put("id", user.id.toString())
        .put("username", user.username)
        .put("discriminator", user.discriminator.toString())
        .put("avatar", user.avatar ?: JSONObject.NULL)

    private fun VoiceState.getField(type: String) = ReflectUtils.getField(this, type) == true
}

package com.github.secp192k1

import android.content.Context
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.GatewayAPI
import com.aliucord.entities.Plugin
import com.aliucord.patcher.before
import com.aliucord.patcher.component1
import com.aliucord.patcher.component2
import com.aliucord.patcher.component3
import com.aliucord.utils.GsonUtils.fromJson
import com.aliucord.wrappers.ChannelWrapper.Companion.guildId
import com.aliucord.wrappers.ChannelWrapper.Companion.id
import com.discord.api.embeddedactivities.EmbeddedActivityInboundUpdate
import com.discord.gateway.GatewaySocket
import com.discord.models.deserialization.gson.InboundGatewayGsonParser
import com.discord.stores.StoreStream
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap


@AliucordPlugin(requiresRestart = true)
@Suppress("unused")
class ActivitiesV2 : Plugin() {
    private val launched: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    init {
        settingsTab = SettingsTab(Settings::class.java, SettingsTab.Type.BOTTOM_SHEET).withArgs(settings)
        Config.attach(settings)
    }

    private companion object {
        const val V1 = "EMBEDDED_ACTIVITY_UPDATE"
        const val V2 = "EMBEDDED_ACTIVITY_UPDATE_V2"

        // V2 is handled by this plugin
        // this is to avoid the "is unhandled!" spam
        val SILENCED_EVENTS = setOf(
            V2,
            "OAUTH2_TOKEN_CREATE",
            "OAUTH2_TOKEN_DELETE",
            "OAUTH2_TOKEN_REVOKE",
            "USER_APPLICATION_UPDATE",
        )
    }

    override fun start(context: Context) {
        EmbeddedActivityHost.preload(context)
        ActivityPicker.patch(patcher)
        ActivityApi.trackInteractionEvents()
        EmbeddedActivityHost.onLeave = { session ->
            launched.remove(session.rawInstanceId)
            dispatchLeave(session)
        }
        patcher.before<GatewaySocket>(
            "handleDispatch",
            Any::class.java,
            String::class.javaObjectType,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Long::class.javaPrimitiveType!!,
        ) { (param, _: Any?, event: String) ->
            if (event in SILENCED_EVENTS) param.result = null
        }

        GatewayAPI.onRawEvent(V2) { raw -> Utils.threadPool.execute { handleV2Update(raw) } }
    }

    private fun handleV2Update(raw: String) {
        try {
            val instance = JSONObject(raw).optJSONObject("d") ?: return
            val location = instance.optJSONObject("location") ?: return
            val channelId = location.optString("channel_id")
            val applicationId = instance.optString("application_id")
            if (channelId.isEmpty() || applicationId.isEmpty()) return

            val channel = StoreStream.getChannels().getChannel(channelId.toLong())
            val userIds = mutableListOf<String>()
            instance.optJSONArray("participants")?.let { participants ->
                for (i in 0 until participants.length()) {
                    val userId = participants.getJSONObject(i).optString("user_id")
                    if (userId.isNotEmpty()) userIds.add(userId)
                }
            }

            val users = JSONArray()
            userIds.forEach { users.put(it) }

            val embeddedActivity = JSONObject()
                .put("application_id", applicationId)
                .put("name", ActivityApi.fetchAppName(applicationId) ?: "Unknown Activity")

            val v1 = JSONObject()
                .put("channel_id", channelId)
                .put("users", users)
                .put("embedded_activity", embeddedActivity)
            if (location.has("guild_id") && !location.isNull("guild_id"))
                v1.put("guild_id", location.optString("guild_id"))

            val update = InboundGatewayGsonParser.INSTANCE.gatewayGsonInstance
                .fromJson(v1.toString(), EmbeddedActivityInboundUpdate::class.java)
            StoreStream.getGatewaySocket().handleDispatch(V1, update)

            val instanceId = instance.optString("instance_id")
            if (channel != null && userIds.contains(StoreStream.getUsers().me.id.toString()) && launched.add(instanceId)) {
                val compositeInstanceId = instance.optString("composite_instance_id").ifEmpty { instanceId }

                Utils.mainThread.post {
                    if (!EmbeddedActivityHost.open(
                            applicationId,
                            compositeInstanceId,
                            instanceId,
                            instance.optString("launch_id"),
                            channel,
                            location.optString("id"),
                        )
                    ) launched.remove(instanceId)
                }
            }

            // After the open post so the initial update isn't dropped before the session exists
            EmbeddedActivityHost.updateParticipants(instanceId, userIds.mapNotNull { it.toLongOrNull() })
        } catch (e: Throwable) {
            logger.error("Failed to handle $V2", e)
        }
    }

    private fun dispatchLeave(session: ActivitySession) {
        try {
            val v1 = JSONObject()
                .put("channel_id", session.channel.id)
                .put("users", JSONArray())
                .put("embedded_activity", JSONObject().put("application_id", session.applicationId))
            session.channel.guildId.takeIf { it != 0L }?.let { v1.put("guild_id", it.toString()) }
            val update = InboundGatewayGsonParser.INSTANCE.gatewayGsonInstance
                .fromJson(v1.toString(), EmbeddedActivityInboundUpdate::class.java)
            StoreStream.getGatewaySocket().handleDispatch(V1, update)
        } catch (e: Throwable) {
            logger.error("Failed to dispatch activity leave", e)
        }
    }

    override fun stop(context: Context) = patcher.unpatchAll()
}

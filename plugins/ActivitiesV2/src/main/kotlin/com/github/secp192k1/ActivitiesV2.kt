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
import com.aliucord.wrappers.ChannelWrapper.Companion.type
import com.discord.api.botuikit.Component
import com.discord.api.embeddedactivities.EmbeddedActivityInboundUpdate
import com.discord.api.message.MessageTypes
import com.discord.api.message.embed.MessageEmbed
import com.discord.gateway.GatewaySocket
import com.discord.models.deserialization.gson.InboundGatewayGsonParser
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreApplicationInteractions
import com.discord.stores.StoreMessagesLoader
import com.discord.stores.StoreStream
import com.google.gson.reflect.TypeToken
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Field
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.discord.api.message.Message as ApiMessage
import com.discord.models.message.Message as ModelMessage


@AliucordPlugin(requiresRestart = true)
@Suppress("unused")
class ActivitiesV2 : Plugin() {
    private val launched: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    private val thread: ExecutorService = Executors.newSingleThreadExecutor()
    private val fieldCache = ConcurrentHashMap<String, Field>()

    private fun field(obj: Any, name: String): Field = fieldCache.getOrPut("${obj.javaClass.name}#$name") {
        var cls: Class<*>? = obj.javaClass

        while (cls != null) {
            try {
                return@getOrPut cls.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }

        throw NoSuchFieldException("${obj.javaClass.name}#$name")
    }

    private fun getField(obj: Any, name: String): Any? = field(obj, name).get(obj)

    private fun setField(obj: Any, name: String, value: Any?) = field(obj, name).set(obj, value)

    init {
        settingsTab = SettingsTab(Settings::class.java, SettingsTab.Type.BOTTOM_SHEET).withArgs(settings)
        Config.attach(settings)
    }

    private companion object {
        const val V1 = "EMBEDDED_ACTIVITY_UPDATE"
        const val V2 = "EMBEDDED_ACTIVITY_UPDATE_V2"
        const val CUSTOM_ID_PREFIX = "activitiesv2"
        const val EMBED_COLOR = 0x248046

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

        GatewayAPI.onRawEvent(V2) { raw -> thread.execute { handleV2Update(raw) } }
        patchLaunchMessages()
    }

    // Backport of the launch messages (type 23)
    private fun patchLaunchMessages() {
        patcher.before<StoreStream>(
            "handleMessageCreate",
            ApiMessage::class.java,
        ) { (_, msg: ApiMessage) -> injectLaunchCard(msg) }

        patcher.before<StoreStream>(
            "handleMessageUpdate",
            ApiMessage::class.java,
        ) { (_, msg: ApiMessage) -> injectLaunchCard(msg) }

        patcher.before<StoreStream>(
            "handleMessagesLoaded",
            StoreMessagesLoader.ChannelChunk::class.java,
        ) { (_, chunk: StoreMessagesLoader.ChannelChunk) ->
            chunk.messages.forEach(::injectLaunchCard)
        }

        patcher.before<StoreApplicationInteractions>(
            "sendComponentInteraction",
            Long::class.javaPrimitiveType!!,
            Long::class.javaObjectType,
            Long::class.javaPrimitiveType!!,
            Long::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            RestAPIParams.ComponentInteractionData::class.java,
            Long::class.javaObjectType,
        ) { param ->
            val data =
                param.args[5] as? RestAPIParams.ComponentInteractionData.ButtonComponentInteractionData ?: return@before
            val customId = data.customId ?: return@before
            if (!customId.startsWith("$CUSTOM_ID_PREFIX:")) return@before
            param.result = null

            val parts = customId.split(':')
            val appId = parts.getOrNull(1) ?: return@before
            val channelId = parts.getOrNull(2)?.toLongOrNull() ?: return@before
            val channel = StoreStream.getChannels().getChannel(channelId)
            val guildId = channel?.guildId ?: 0L
            val voice = channel?.let { ChannelType.from(it.type)?.isVoice } == true

            Utils.threadPool.execute {
                val name = ActivityApi.fetchAppName(appId) ?: "Activity"
                ActivityApi.launch(channelId, guildId, appId, name, voice) { reason ->
                    Utils.showToast(if (reason != null) "Failed to launch $name: $reason" else "Failed to launch $name")
                }
            }
        }
    }

    private fun isLaunchMessage(type: Int?, content: String?, interactionName: String?) =
        type == MessageTypes.CONTEXT_MENU_COMMAND && content.isNullOrEmpty() && interactionName == "launch"

    private fun injectLaunchCard(msg: ApiMessage) {
        try {
            if (!isLaunchMessage(
                getField(msg, "type") as? Int,
                getField(msg, "content") as? String,
                msg.p()?.b()
            )) return

            applyLaunchCard(msg, msg.b(), getField(msg, "channelId") as? Long ?: return)
        } catch (e: Throwable) {
            logger.error("Failed to patch launch message", e)
        }
    }

    private fun injectLaunchCard(msg: ModelMessage) {
        try {
            if (!isLaunchMessage(msg.type, msg.content, msg.interaction?.b())) return

            applyLaunchCard(msg, msg.application, msg.channelId)
        } catch (e: Throwable) {
            logger.error("Failed to patch launch message", e)
        }
    }

    private fun applyLaunchCard(msg: Any, application: Any?, channelId: Long) {
        val appId = application?.let { getField(it, "id") as? Long }?.toString()

        if (appId == null) {
            // Fallback to a plain text body so it's at least visible
            setField(msg, "content", "Started an activity")
            return
        }

        val appName = getField(application, "name") as? String ?: "Activity"

        val embedJson = JSONObject()
            .put("type", "rich")
            .put("author", JSONObject().put("name", "Game Invitation"))
            .put("title", appName)
            .put("description", "Tap **Play** to join the activity")
            .put("color", EMBED_COLOR)

        val componentsJson = JSONArray().put(
            JSONObject()
                .put("type", 1)
                .put(
                    "components",
                    JSONArray().put(
                        JSONObject()
                            .put("type", 2)
                            .put("style", 2)
                            .put("label", "Play")
                            .put("custom_id", "$CUSTOM_ID_PREFIX:$appId:$channelId"),
                    ),
                ),
        )

        val gson = InboundGatewayGsonParser.INSTANCE.gatewayGsonInstance
        val embed = gson.fromJson(embedJson.toString(), MessageEmbed::class.java)
        val components: List<Component> = gson.fromJson(
            componentsJson.toString(),
            TypeToken.getParameterized(List::class.java, Component::class.java).type,
        )

        setField(msg, "embeds", listOf(embed))
        setField(msg, "components", components)
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
                .put("channel_id", session.channel.id.toString())
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

    override fun stop(context: Context) {
        logger.info("Stopping")
        thread.shutdown()
        patcher.unpatchAll()
    }
}

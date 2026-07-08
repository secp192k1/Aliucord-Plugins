package com.github.secp192k1

import com.aliucord.Logger
import com.aliucord.Utils
import com.discord.stores.StoreStream
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

internal class ActivityRpc(
    private val session: ActivitySession,
    private val post: (String) -> Unit,
) {
    private companion object {
        const val PARTICIPANTS_UPDATE = "ACTIVITY_INSTANCE_PARTICIPANTS_UPDATE"
    }

    private val logger = Logger("ActivitiesV2")
    private val subscriptions: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    @Volatile private var clientId = ""
    @Volatile private var lastParticipants: JSONObject? = null

    fun handle(json: String) {
        val tuple = JSONArray(json)

        when (RpcOpcode.from(tuple.optInt(0, -1))) {
            RpcOpcode.HANDSHAKE -> {
                clientId = tuple.optJSONObject(1)?.optString("client_id").orEmpty()
                frame(
                    JSONObject().put("cmd", "DISPATCH").put("evt", "READY").put("nonce", JSONObject.NULL)
                        .put("data", JSONObject().put("v", 1).put("config", JSONObject()
                            .put("cdn_host", "cdn.discordapp.com")
                            .put("api_endpoint", "//discord.com/api")
                            .put("environment", "production"))
                            .put("user", currentUser()))
                )
            }
            RpcOpcode.FRAME -> {
                val payload = tuple.optJSONObject(1) ?: return
                val command = payload.optString("cmd")
                val nonce = payload.opt("nonce")
                val event = payload.optString("evt").ifEmpty { null }
                val args = payload.optJSONObject("args") ?: JSONObject()
                if (command.isEmpty()) return
                when (RpcCommand.from(command)) {
                    RpcCommand.AUTHORIZE -> Utils.threadPool.execute { onResult(command, nonce, ActivityApi.authorize(session, clientId, args)) }
                    RpcCommand.AUTHENTICATE -> Utils.threadPool.execute { onResult(command, nonce, ActivityApi.authenticate(args.optString("access_token"))) }
                    RpcCommand.GET_CHANNEL -> reply(command, nonce, ActivityData.channel(session, args))
                    RpcCommand.GET_CHANNEL_PERMISSIONS -> reply(command, nonce, ActivityData.permissions(session))
                    RpcCommand.ENCOURAGE_HW_ACCELERATION -> reply(command, nonce, JSONObject().put("enabled", true))
                    RpcCommand.SUBSCRIBE -> {
                        event?.let(subscriptions::add)
                        reply(command, nonce, JSONObject().put("evt", event))
                        if (event == PARTICIPANTS_UPDATE) lastParticipants?.let { dispatch(PARTICIPANTS_UPDATE, it) }
                    }
                    RpcCommand.UNSUBSCRIBE -> {
                        event?.let(subscriptions::remove)
                        reply(command, nonce, JSONObject().put("evt", event))
                    }
                    RpcCommand.SET_ACTIVITY -> {
                        val activity = args.optJSONObject("activity") ?: JSONObject()
                        if (!activity.has("name")) activity.put("name", "")
                        if (!activity.has("type")) activity.put("type", ActivityType.PLAYING.value)
                        reply(command, nonce, activity)
                    }
                    RpcCommand.CAPTURE_LOG, RpcCommand.SEND_ANALYTICS_EVENT, RpcCommand.GET_PLATFORM_BEHAVIORS,
                    RpcCommand.SET_ORIENTATION_LOCK_STATE -> reply(command, nonce, JSONObject())
                    null -> {
                        logger.warn("Unhandled RPC command: $command args=$args")
                        replyError(command, nonce, RpcErrorCode.INVALID_COMMAND.value, "Unknown command: $command")
                    }
                }
            }
            null -> logger.warn("Unknown RPC opcode: ${tuple.optInt(0, -1)}")
        }
    }

    private fun currentUser(): JSONObject {
        val user = JSONObject()

        try {
            val me = StoreStream.getUsers().me
            user.put("id", me.id.toString())
                .put("username", me.username)
                .put("discriminator", me.discriminator.toString())
                .put("avatar", me.avatar ?: JSONObject.NULL)
        } catch (e: Throwable) {
            logger.error("Failed to build READY user object", e)
        }

        return user
    }

    fun updateParticipants(data: JSONObject) {
        lastParticipants = data
        if (PARTICIPANTS_UPDATE in subscriptions) dispatch(PARTICIPANTS_UPDATE, data)
    }

    private fun dispatch(event: String, data: JSONObject) =
        frame(JSONObject()
            .put("cmd", "DISPATCH")
            .put("evt", event)
            .put("nonce", JSONObject.NULL)
            .put("data", data))

    private fun onResult(cmd: String, nonce: Any?, result: ApiResult) = when (result) {
        is ApiResult.OK -> reply(cmd, nonce, result.data)
        is ApiResult.ERROR -> replyError(cmd, nonce, result.code, result.message)
    }

    private fun deliver(tuple: JSONArray) = post("window.__hostDeliver(${JSONObject.quote(tuple.toString())})")

    private fun frame(payload: JSONObject) = deliver(JSONArray().put(RpcOpcode.FRAME.value).put(payload))

    private fun reply(cmd: String, nonce: Any?, data: JSONObject) =
        frame(JSONObject()
            .put("cmd", cmd)
            .put("evt", JSONObject.NULL)
            .put("nonce", nonce ?: JSONObject.NULL)
            .put("data", data))

    private fun replyError(cmd: String, nonce: Any?, code: Int, message: String) =
        frame(JSONObject()
            .put("cmd", cmd)
            .put("evt", "ERROR")
            .put("nonce", nonce ?: JSONObject.NULL)
            .put("data", JSONObject()
                .put("code", code)
                .put("message", message)))
}

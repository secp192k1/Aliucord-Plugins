package com.github.secp192k1

import com.aliucord.Logger
import com.aliucord.Utils
import org.json.JSONArray
import org.json.JSONObject

internal class ActivityRpc(
    private val session: ActivitySession,
    private val post: (String) -> Unit,
) {
    private val logger = Logger("ActivitiesV2")
    @Volatile private var clientId = ""

    fun handle(json: String) {
        val tuple = JSONArray(json)
        when (tuple.optInt(0, -1)) {
            HANDSHAKE -> {
                clientId = tuple.optJSONObject(1)?.optString("client_id").orEmpty()
                frame(
                    JSONObject().put("cmd", "DISPATCH").put("evt", "READY").put("nonce", JSONObject.NULL)
                        .put("data", JSONObject().put("v", 1).put("config", JSONObject()
                            .put("cdn_host", "cdn.discordapp.com")
                            .put("api_endpoint", "//discord.com/api")
                            .put("environment", "production")))
                )
            }
            FRAME -> {
                val payload = tuple.optJSONObject(1) ?: return
                val command = payload.optString("cmd")
                val nonce = payload.opt("nonce")
                val event = payload.optString("evt").ifEmpty { null }
                val args = payload.optJSONObject("args") ?: JSONObject()
                when (command) {
                    "AUTHORIZE" -> Utils.threadPool.execute { onResult("AUTHORIZE", nonce, ActivityApi.authorize(session, clientId, args)) }
                    "AUTHENTICATE" -> Utils.threadPool.execute { onResult("AUTHENTICATE", nonce, ActivityApi.authenticate(args.optString("access_token"))) }
                    "GET_CHANNEL" -> reply(command, nonce, ActivityData.channel(session))
                    "GET_CHANNEL_PERMISSIONS" -> reply(command, nonce, ActivityData.permissions(session))
                    "ENCOURAGE_HW_ACCELERATION" -> reply(command, nonce, JSONObject().put("enabled", true))
                    "SUBSCRIBE", "UNSUBSCRIBE" -> reply(command, nonce, JSONObject().put("evt", event))
                    "CAPTURE_LOG", "SEND_ANALYTICS_EVENT", "SET_ACTIVITY", "GET_PLATFORM_BEHAVIORS" -> reply(command, nonce, JSONObject())
                    "" -> {}
                    else -> {
                        logger.warn("Unhandled RPC command: $command args=$args")
                        reply(command, nonce, JSONObject())
                    }
                }
            }
        }
    }

    private fun onResult(cmd: String, nonce: Any?, result: ApiResult) = when (result) {
        is ApiResult.OK -> reply(cmd, nonce, result.data)
        is ApiResult.ERROR -> replyError(cmd, nonce, result.code, result.message)
    }

    private fun deliver(tuple: JSONArray) = post("window.__hostDeliver(${JSONObject.quote(tuple.toString())})")

    private fun frame(payload: JSONObject) = deliver(JSONArray().put(FRAME).put(payload))

    private fun reply(cmd: String, nonce: Any?, data: JSONObject) =
        frame(JSONObject().put("cmd", cmd).put("evt", JSONObject.NULL).put("nonce", nonce ?: JSONObject.NULL).put("data", data))

    private fun replyError(cmd: String, nonce: Any?, code: Int, message: String) =
        frame(JSONObject().put("cmd", cmd).put("evt", "ERROR").put("nonce", nonce ?: JSONObject.NULL)
            .put("data", JSONObject().put("code", code).put("message", message)))

    companion object {
        private const val HANDSHAKE = 0
        private const val FRAME = 1
    }
}

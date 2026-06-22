package com.github.secp192k1

import android.net.Uri
import com.aliucord.Http
import com.aliucord.Logger
import com.aliucord.Utils
import com.aliucord.utils.IOUtils
import com.aliucord.utils.ReflectUtils
import com.discord.stores.StoreStream
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

internal sealed class ApiResult {
    class OK(val data: JSONObject) : ApiResult()
    class ERROR(val code: Int, val message: String) : ApiResult()
}

internal object ActivityApi {
    private const val APP_VERSION = "335.1"
    private val logger = Logger("ActivitiesV2")

    fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    fun buildUrl(session: ActivitySession): String {
        val params = StringBuilder("instance_id=").append(urlEncode(session.instanceId))
            .append("&location_id=").append(urlEncode(session.locationId))
            .append("&launch_id=").append(urlEncode(session.launchId))
            .append("&channel_id=").append(urlEncode(session.channelId))
            .append("&frame_id=").append(UUID.randomUUID())
            .append("&platform=mobile")
            .append("&mobile_app_version=").append(APP_VERSION)
        session.guildId?.let { params.append("&guild_id=").append(urlEncode(it)) }
        return "https://${session.applicationId}.discordsays.com/?$params"
    }

    fun authorize(session: ActivitySession, clientId: String, args: JSONObject): ApiResult {
        return try {
            val authClientId = args.optString("client_id").ifEmpty { clientId }
            val scopesArray = args.optJSONArray("scope") ?: args.optJSONArray("scopes") ?: JSONArray()
            val scopes = buildString {
                for (i in 0 until scopesArray.length()) {
                    if (i > 0) append("%20")
                    append(scopesArray.optString(i))
                }
            }
            val state = args.optString("state")
            val route = "/oauth2/authorize?client_id=${urlEncode(authClientId)}&response_type=code&scope=$scopes&state=${urlEncode(state)}"

            val locationContext = JSONObject().put("channel_id", session.channelId).put("channel_type", 0)
            session.guildId?.let { locationContext.put("guild_id", it) }
            val body = JSONObject()
                .put("authorize", true)
                .put("integration_type", 1)
                .put("location_context", locationContext)

            val request = Http.Request.newDiscordRNRequest(route, "POST")
                .setHeader("Content-Type", "application/json")
            val res = request.executeWithBody(body.toString())
            if (!res.ok()) {
                val error = request.conn.errorStream?.let { IOUtils.readAsText(it) }.orEmpty()
                logger.error("AUTHORIZE ${res.statusCode} ${res.statusMessage} body=$error", null)
                return ApiResult.ERROR(4011, "authorize failed: ${res.statusCode}")
            }
            val text = res.text()
            val code = Uri.parse(JSONObject(text).optString("location")).getQueryParameter("code")
                ?: run {
                    logger.error("AUTHORIZE ${res.statusCode} no code, body=$text", null)
                    return ApiResult.ERROR(4011, "no code in response")
                }
            ApiResult.OK(JSONObject().put("code", code))
        } catch (e: Throwable) {
            logger.error("AUTHORIZE failed", e)
            ApiResult.ERROR(4011, e.message ?: "authorize error")
        }
    }

    fun authenticate(token: String): ApiResult {
        return try {
            val request = Http.Request.newDiscordRNRequest("/oauth2/@me", "GET")
                .setHeader("Authorization", "Bearer $token")
            val res = request.execute()
            if (!res.ok()) {
                val error = request.conn.errorStream?.let { IOUtils.readAsText(it) }.orEmpty()
                logger.error("AUTHENTICATE ${res.statusCode} ${res.statusMessage} body=$error", null)
                return ApiResult.ERROR(4009, "authenticate failed: ${res.statusCode}")
            }
            ApiResult.OK(JSONObject(res.text()).put("access_token", token))
        } catch (e: Throwable) {
            logger.error("AUTHENTICATE failed", e)
            ApiResult.ERROR(4009, e.message ?: "authenticate error")
        }
    }

    fun leave(session: ActivitySession) {
        val app = session.applicationId
        val locationId = session.locationId
        val instance = session.rawInstanceId
        if (app.isEmpty() || locationId.isEmpty() || instance.isEmpty()) return
        Utils.threadPool.execute {
            try {
                val sessionId = ReflectUtils.getField(StoreStream.getInteractions(), "sessionId") as? String
                Http.Request.newDiscordRNRequest("/applications/$app/activities/$locationId/instances/$instance/leave", "POST")
                    .setHeader("Content-Type", "application/json")
                    .executeWithBody(JSONObject().put("session_id", sessionId.orEmpty()).toString())
            } catch (e: Throwable) {
                logger.error("Failed to leave activity instance", e)
            }
        }
    }
}

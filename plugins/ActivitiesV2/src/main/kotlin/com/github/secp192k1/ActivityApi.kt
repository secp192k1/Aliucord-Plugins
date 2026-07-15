package com.github.secp192k1

import android.net.Uri
import com.aliucord.Http
import com.aliucord.Logger
import com.aliucord.Utils
import com.aliucord.api.GatewayAPI
import com.aliucord.utils.IOUtils
import com.aliucord.utils.ReflectUtils
import com.aliucord.wrappers.ChannelWrapper.Companion.guildId
import com.aliucord.wrappers.ChannelWrapper.Companion.id
import com.aliucord.wrappers.ChannelWrapper.Companion.type
import com.discord.stores.StoreStream
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal sealed class ApiResult {
    class OK(val data: JSONObject) : ApiResult()
    class ERROR(val code: Int, val message: String) : ApiResult()
}

internal object ActivityApi {
    private const val APP_VERSION = "335.1"
    private val logger = Logger("ActivitiesV2")
    private val entryPointCommands = ConcurrentHashMap<String, JSONObject>()
    private val pendingLaunches = ConcurrentHashMap<String, String>()
    private val appNames = ConcurrentHashMap<String, String>()

    fun trackInteractionEvents() {
        GatewayAPI.onRawEvent("INTERACTION_SUCCESS") { raw ->
            JSONObject(raw).optJSONObject("d")?.optString("nonce")?.let { pendingLaunches.remove(it) }
        }

        GatewayAPI.onRawEvent("INTERACTION_FAILURE") { raw ->
            val data = JSONObject(raw).optJSONObject("d") ?: return@onRawEvent
            val name = pendingLaunches.remove(data.optString("nonce")) ?: return@onRawEvent
            val code = data.optInt("reason_code")
            logger.error("Launch of $name failed: reason_code=$code", null)
            Utils.showToast("$name: ${InteractionFailureReason.messageFor(code)}")
        }
    }

    fun fetchAppName(applicationId: String): String? {
        appNames[applicationId]?.let { return it.ifEmpty { null } }

        return try {
            val res = Http.Request.newDiscordRNRequest("/applications/public?application_ids=$applicationId", "GET").execute()
            if (!res.ok()) {
                logger.error("applications/public failed: ${res.statusCode} ${res.statusMessage}", null)
                return null
            }

            val name = JSONArray(res.text()).optJSONObject(0)?.optString("name")
            if (name.isNullOrEmpty()) {
                // Definitive "no name" answer,
                // cache it so this app never refetches
                appNames[applicationId] = ""
                return null
            }

            appNames[applicationId] = name
            name
        } catch (e: Throwable) {
            logger.error("Failed to fetch application name for $applicationId", e)
            null
        }
    }

    fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    fun buildUrl(session: ActivitySession): String {
        val params = StringBuilder("instance_id=").append(urlEncode(session.instanceId))
            .append("&location_id=").append(urlEncode(session.locationId))
            .append("&launch_id=").append(urlEncode(session.launchId))
            .append("&channel_id=").append(urlEncode(session.channel.id.toString()))
            .append("&frame_id=").append(UUID.randomUUID())
            .append("&platform=mobile")
            .append("&mobile_app_version=").append(APP_VERSION)
        session.channel.guildId.takeIf { it != 0L }?.let { params.append("&guild_id=").append(it) }
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

            val locationContext = JSONObject()
                .put("channel_id", session.channel.id.toString())
                .put("channel_type", session.channel.type)
            session.channel.guildId.takeIf { it != 0L }?.let { locationContext.put("guild_id", it.toString()) }
            val body = JSONObject()
                .put("authorize", true)
                .put("integration_type", ApplicationIntegrationType.USER_INSTALL.value)
                .put("location_context", locationContext)

            val request = Http.Request.newDiscordRNRequest(route, "POST")
                .setHeader("Content-Type", "application/json")
            val res = request.executeWithBody(body.toString())
            if (!res.ok()) {
                val error = request.conn.errorStream?.let { IOUtils.readAsText(it) }.orEmpty()
                logger.error("AUTHORIZE ${res.statusCode} ${res.statusMessage} body=$error", null)
                return ApiResult.ERROR(RpcErrorCode.AUTHORIZE_FAILED.value, "authorize failed: ${res.statusCode}")
            }
            val text = res.text()
            val code = Uri.parse(JSONObject(text).optString("location")).getQueryParameter("code")
                ?: run {
                    logger.error("AUTHORIZE ${res.statusCode} no code, body=$text", null)
                    return ApiResult.ERROR(RpcErrorCode.AUTHORIZE_FAILED.value, "no code in response")
                }
            ApiResult.OK(JSONObject().put("code", code))
        } catch (e: Throwable) {
            logger.error("AUTHORIZE failed", e)
            ApiResult.ERROR(RpcErrorCode.AUTHORIZE_FAILED.value, e.message ?: "authorize error")
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
                return ApiResult.ERROR(RpcErrorCode.AUTHENTICATE_FAILED.value, "authenticate failed: ${res.statusCode}")
            }
            ApiResult.OK(JSONObject(res.text()).put("access_token", token))
        } catch (e: Throwable) {
            logger.error("AUTHENTICATE failed", e)
            ApiResult.ERROR(RpcErrorCode.AUTHENTICATE_FAILED.value, e.message ?: "authenticate error")
        }
    }

    fun launch(
        channelId: Long,
        guildId: Long,
        applicationId: String,
        applicationName: String,
        voice: Boolean = false,
        onError: (reason: String?) -> Unit
    ) {
        Utils.threadPool.execute {
            val nonce = Utils.generateRNNonce().toString()
            try {
                val sessionId = ReflectUtils.getField(StoreStream.getInteractions(), "sessionId") as? String
                val command = if (voice) null else fetchEntryPointCommand(applicationId)
                val request: Http.Request
                val body: JSONObject

                if (command != null) {
                    pendingLaunches[nonce] = applicationName
                    request = Http.Request.newDiscordRNRequest("/interactions", "POST")
                    body = JSONObject()
                        .put("type", InteractionType.APPLICATION_COMMAND.value)
                        .put("application_id", applicationId)
                        .put("channel_id", channelId.toString())
                        .put("session_id", sessionId.orEmpty())
                        .put("nonce", nonce)
                        .put("analytics_location", "activities_mini_shelf")
                        .put("section_name", "activities")
                        .put("source", "TEXT")
                        .put("data", JSONObject()
                            .put("id", command.getString("id"))
                            .put("name", command.getString("name"))
                            .put("type", ApplicationCommandType.PRIMARY_ENTRY_POINT.value)
                            .put("version", command.getString("version"))
                            .put("application_command", command))
                    if (guildId != 0L) body.put("guild_id", guildId.toString())
                } else {
                    request = Http.Request.newDiscordRNRequest("/activities/$channelId/$applicationId", "POST")
                    body = JSONObject().put("session_id", sessionId.orEmpty())
                }

                request.setHeader("Content-Type", "application/json")
                val res = request.executeWithBody(body.toString())
                if (!res.ok()) {
                    nonce.let(pendingLaunches::remove)
                    val error = request.conn.errorStream?.let { IOUtils.readAsText(it) }.orEmpty()
                    logger.error("LAUNCH ${res.statusCode} ${res.statusMessage} body=$error", null)
                    val reason = runCatching { JSONObject(error).optString("message") }.getOrNull()?.ifEmpty { null }
                    onError(reason)
                }
            } catch (e: Throwable) {
                nonce.let(pendingLaunches::remove)
                logger.error("Failed to launch activity $applicationId", e)
                onError(e.message)
            }
        }
    }

    private fun fetchEntryPointCommand(applicationId: String): JSONObject? {
        entryPointCommands[applicationId]?.let { return it }
        return try {
            val request = Http.Request.newDiscordRNRequest("/applications/$applicationId/application-command-index", "GET")
            val res = request.execute()
            if (!res.ok()) {
                val error = request.conn.errorStream?.let { IOUtils.readAsText(it) }.orEmpty()
                logger.error("COMMAND_INDEX ${res.statusCode} ${res.statusMessage} body=$error", null)
                return null
            }

            val commands = JSONObject(res.text()).optJSONArray("application_commands") ?: return null
            var found: JSONObject? = null

            for (i in 0 until commands.length()) {
                val command = commands.getJSONObject(i)

                if (command.optInt("type") == ApplicationCommandType.PRIMARY_ENTRY_POINT.value) {
                    found = command
                    break
                }

                if (found == null && command.optString("name") == "launch") found = command
            }

            found?.also { entryPointCommands[applicationId] = it }
        } catch (e: Throwable) {
            logger.error("Failed to fetch command index for $applicationId", e)
            null
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

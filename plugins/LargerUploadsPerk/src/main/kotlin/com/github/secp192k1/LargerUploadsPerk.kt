package com.github.secp192k1

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.util.Base64
import android.view.View
import com.aliucord.Http
import com.aliucord.PluginManager
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.GatewayAPI
import com.aliucord.api.PatcherAPI
import com.aliucord.entities.Plugin
import com.aliucord.patcher.before
import com.aliucord.patcher.component1
import com.aliucord.patcher.component2
import com.aliucord.patcher.instead
import com.aliucord.utils.GsonUtils
import com.aliucord.utils.RNSuperProperties
import com.discord.api.message.Message
import com.discord.api.premium.PremiumTier
import com.discord.models.user.User
import com.discord.restapi.utils.CountingRequestBody
import com.discord.stores.StoreStream
import com.discord.utilities.messagesend.`MessageQueue$doSend$2`
import com.discord.utilities.premium.PremiumUtils
import com.discord.utilities.rest.AttachmentRequestBody
import com.discord.utilities.rest.SendUtils
import com.discord.utilities.rest.SendUtilsKt
import com.discord.widgets.chat.MessageManager
import com.discord.widgets.chat.MessageManager.AttachmentValidationResult
import com.discord.widgets.chat.MessageManager.AttachmentsRequest
import com.lytefast.flexinput.model.Attachment
import de.robv.android.xposed.XposedBridge
import org.json.JSONObject
import rx.subjects.BehaviorSubject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import b.a.a.c as ImageUploadFailedDialog

@AliucordPlugin(requiresRestart = true)
@Suppress("unused")
class LargerUploadsPerk : Plugin() {
    private companion object {
        const val DEFAULT_MAX_FILE_SIZE = 10
        const val BYTES_PER_MB = 1024L * 1024L  // MiB, not MB
        const val USER_AGENT = "Discord-Android/341200;RNA"
        val uploadFeatures = mapOf(
            "MAX_FILE_SIZE_50_MB" to 50,
            "MAX_FILE_SIZE_100_MB" to 100,
            "MAX_FILE_SIZE_250_MB" to 250,
        )
    }

    private val customSuperProps: String by lazy {
        val props = JSONObject(RNSuperProperties.superProperties.toString())
            .put("client_version", "341.0 - rn")
            .put("client_build_number", 6081)
            .put("release_channel", "canaryRelease")
            .put("has_client_mods", false)
            .put("launch_signature", (System.currentTimeMillis() * 1_000_000L).toString())

        Base64.encodeToString(props.toString().toByteArray(), Base64.NO_WRAP)
    }

    private val limits = ConcurrentHashMap<Long, Int>()
    private val fetching = AtomicBoolean()


    @Suppress("UNCHECKED_CAST")
    override fun start(context: Context) {
        unpatchCoreUploadSize()
        watchGuilds()

        // Base caps guild uploads by boost tier alone and ignores the perk features
        patcher.instead<PremiumUtils>("getGuildMaxFileSizeMB", Int::class.java) { (_, tier: Int) ->
            val guildId: Long = StoreStream.getGuildSelected().selectedGuildId

            val base = when (tier) {
                2 -> 50
                3 -> 100
                else -> DEFAULT_MAX_FILE_SIZE
            }

            maxOf(base, limits[guildId] ?: 0)
        }

        patcher.instead<PremiumUtils>("getMaxFileSizeMB", User::class.java) { (_, user: User) ->
            when (user.premiumTier!!) {
                PremiumTier.TIER_0 -> 50 // Nitro Basic
                PremiumTier.TIER_1 -> 50 // Nitro Classic
                PremiumTier.TIER_2 -> 500 // Nitro
                else -> DEFAULT_MAX_FILE_SIZE
            }
        }

        // Validate per attachment rather than per message, so one big file doesn't block the rest
        patcher.instead<MessageManager>("validateAttachments", AttachmentsRequest::class.java) { (_, request: AttachmentsRequest?) ->
            if (request == null) {
                return@instead AttachmentValidationResult.EmptyAttachments.INSTANCE
            }

            val attachments: List<Attachment<*>>? = request.attachments
            if (!attachments.isNullOrEmpty()) {
                for (attachment in attachments) {
                    val bytes = SendUtilsKt.computeFileSizeBytes(attachment.uri, context.contentResolver)
                    if (bytes > request.maxFileSizeMB * BYTES_PER_MB) {
                        return@instead AttachmentValidationResult.FilesTooLarge(request)
                    }
                }
                return@instead AttachmentValidationResult.Success.INSTANCE
            }
            AttachmentValidationResult.EmptyAttachments.INSTANCE
        }

        @SuppressLint("SetTextI18n")
        patcher.instead<ImageUploadFailedDialog>("onViewBound", View::class.java) {
            val maxFileSize = argumentsOrDefault.getInt("PARAM_MAX_FILE_SIZE_MB")
            argumentsOrDefault.putInt("PARAM_MAX_FILE_SIZE_MB", 8)
            XposedBridge.invokeOriginalMethod(it.method, it.thisObject, it.args)
            g().j.text = "Max file size is $maxFileSize MB"

            null
        }

        val countingRequestBody = CountingRequestBody::class.java
        val delegate = countingRequestBody.getDeclaredField("delegate").apply { isAccessible = true }
        val bytesWrittenSubject = countingRequestBody.getDeclaredField("bytesWrittenSubject").apply { isAccessible = true }

        val attachmentRequestBody = AttachmentRequestBody::class.java
        val contentResolverField = attachmentRequestBody.getDeclaredField("contentResolver").apply { isAccessible = true }
        val attachmentField = attachmentRequestBody.getDeclaredField("attachment").apply { isAccessible = true }

        patcher.before<`MessageQueue$doSend$2`<*, *>>("call", SendUtils.SendPayload.ReadyToSend::class.java) {
            val payload = it.args[0] as SendUtils.SendPayload.ReadyToSend
            if (payload.uploads.isEmpty()) return@before

            val channelId = `$message`.channelId
            val attachments = ArrayList<MessagePayload.Attachment>(payload.uploads.size)

            for (upload in payload.uploads) {
                val countingReqBody = upload.part.b
                val reqBody = delegate[countingReqBody]
                val contentResolver = contentResolverField[reqBody] as ContentResolver
                val attachment = attachmentField[reqBody] as Attachment<*>

                contentResolver.openInputStream(attachment.uri)?.use { inputStream ->
                    val initBody = InitAttachmentUpload(
                        arrayOf(InitAttachmentUpload.File(upload.name, upload.contentLength, "0")),
                    )

                    val initRequest = Http.Request.newDiscordRNRequest("/channels/$channelId/attachments", "POST")
                        .setHeader("User-Agent", USER_AGENT)
                        .setHeader("X-Super-Properties", customSuperProps)
                    val initReq = initRequest.executeWithJson(initBody)

                    if (!initReq.ok()) {
                        val error = runCatching {
                            initRequest.conn.errorStream?.bufferedReader()?.use { reader -> reader.readText() }
                        }.getOrNull()

                        logger.error("Failed to init upload: ${initReq.statusCode} ${initReq.statusMessage} $error", null)
                        return@before  // falls back to the legacy upload
                    }


                    val initRes = initReq.json(InitAttachmentUploadRes::class.java).attachments[0]

                    val uploadReq = Http.Request(initRes.upload_url, "PUT")
                        .setHeader("User-Agent", USER_AGENT)
                        .setHeader("Content-Type", upload.mimeType)
                        .setHeader("Content-Length", upload.contentLength.toString())
                    uploadReq.conn.doOutput = true
                    uploadReq.conn.setFixedLengthStreamingMode(upload.contentLength)

                    uploadReq.conn.outputStream.use { outputStream ->
                        var totalBytes = 0L
                        var reportedBytes = 0L
                        var currentBytes: Int
                        val buffer = ByteArray(64 * 1024)
                        val subject = bytesWrittenSubject[countingReqBody] as BehaviorSubject<Long>

                        while (inputStream.read(buffer).also { read -> currentBytes = read } > 0) {
                            outputStream.write(buffer, 0, currentBytes)
                            totalBytes += currentBytes

                            // A 250 MB file is thousands of chunks and every emission redraws the
                            // progress bar, so only report once the number moved a visible amount
                            if (totalBytes - reportedBytes >= BYTES_PER_MB) {
                                reportedBytes = totalBytes
                                subject.onNext(totalBytes)
                            }
                        }

                        outputStream.flush()
                        subject.onNext(totalBytes)
                    }

                    uploadReq.execute().run {
                        if (!ok()) {
                            logger.error("Failed to upload: $statusCode $statusMessage", null)
                            return@before
                        }
                    }

                    attachments.add(
                        MessagePayload.Attachment(
                            attachments.size.toString(),
                            upload.name,
                            initRes.upload_filename,
                            upload.mimeType,
                        )
                    )
                }
            }

            payload.message.run {
                it.result = BehaviorSubject.l0(
                    Http.Request.newDiscordRNRequest("/channels/$channelId/messages", "POST")
                        .setHeader("User-Agent", USER_AGENT)
                        .setHeader("X-Context-Properties", "eyJsb2NhdGlvbiI6ImNoYXRfaW5wdXQifQ==")  // {"location":"chat_input"}
                        .setHeader("X-Super-Properties", customSuperProps)
                        .executeWithJson(
                            GsonUtils.gsonRestApi,
                            MessagePayload(
                                content.trimEnd(),
                                nonce,
                                channelId.toString(),
                                if (messageReference == null) 0 else 19,
                                messageReference,
                                allowedMentions,
                                attachments,
                            ),
                        ).json(GsonUtils.gsonRestApi, Message::class.java),
                )
            }
        }
    }

    // We nuke the UploadSize coreplugin so it doesn't conflict with this one
    // `patcher` is protected so look in the mirror and behold: reflection
    private fun unpatchCoreUploadSize() = runCatching {
        val core = PluginManager.plugins["UploadSize"] ?: return@runCatching
        val patcher = Plugin::class.java.getDeclaredField("patcher").apply { isAccessible = true }

        (patcher[core] as PatcherAPI).unpatchAll()
    }.onFailure {
        logger.error("Failed to unpatch the UploadSize coreplugin", it)
    }

    // Perks can be unlocked while the app runs, so the limits have to follow the gateway.
    // GUILD_CREATE replays every guild on connect, GUILD_UPDATE carries later feature changes
    private fun watchGuilds() {
        fetchGuilds()

        GatewayAPI.onEvent<PartialGuild>("GUILD_CREATE") { putLimit(it) }
        GatewayAPI.onEvent<PartialGuild>("GUILD_UPDATE") { putLimit(it) }
    }

    private fun putLimit(guild: PartialGuild) {
        limits[guild.id] = guild.features.mapNotNull(uploadFeatures::get).maxOrNull() ?: 0
    }

    // Enabling the plugin mid-session misses the GUILD_CREATE burst, so seed the limits once
    private fun fetchGuilds() {
        // For some reason it doesnt check that a request is already in progress..?
        // No spam pls!
        if (!fetching.compareAndSet(false, true)) return

        Utils.threadPool.execute {
            runCatching {
                Http.Request.newDiscordRNRequest("/users/@me/guilds")
                    .setHeader("User-Agent", USER_AGENT)
                    .setHeader("X-Super-Properties", customSuperProps)
                    .execute()
                    .json(Array<PartialGuild>::class.java)
            }.onSuccess { guilds ->
                guilds.forEach(::putLimit)
            }.onFailure {
                logger.error("Failed to list guilds", it)
            }

            fetching.set(false)
        }
    }

    override fun stop(context: Context) = patcher.unpatchAll()
}

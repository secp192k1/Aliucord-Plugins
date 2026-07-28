package com.github.secp192k1

import com.aliucord.entities.RNMessage
import com.discord.restapi.RestAPIParams

@Suppress("unused")
internal class MessagePayload(
    content: String,
    nonce: String,
    val channelId: String,
    val type: Int,
    val messageReference: RestAPIParams.Message.MessageReference?,
    val allowedMentions: RestAPIParams.Message.AllowedMentions?,
    val attachments: List<Attachment>,
) : RNMessage(content = content, nonce = nonce) {
    class Attachment(
        val id: String,
        val filename: String,
        val uploadedFilename: String,
        val originalContentType: String? = null,
    )
}

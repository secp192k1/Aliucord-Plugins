package com.github.secp192k1

import android.content.Context
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.after
import com.aliucord.patcher.before
import com.discord.api.message.Message
import com.discord.stores.StoreMessagesHolder

@AliucordPlugin(requiresRestart = false)
@Suppress("unused")
class FixEditUpdates : Plugin() {
    private val updating = object : ThreadLocal<Boolean>() {
        override fun initialValue() = false
    }

    override fun start(context: Context) {
        patcher.before<StoreMessagesHolder>(
            "updateMessages",
            Message::class.java,
        ) { updating.set(true) }

        patcher.after<StoreMessagesHolder>(
            "updateMessages",
            Message::class.java,
        ) { updating.set(false) }

        patcher.before<StoreMessagesHolder>(
            "isChannelDetached",
            Long::class.javaPrimitiveType!!,
        ) { param ->
            if (updating.get() == true) param.result = false
        }
    }

    override fun stop(context: Context) = patcher.unpatchAll()
}

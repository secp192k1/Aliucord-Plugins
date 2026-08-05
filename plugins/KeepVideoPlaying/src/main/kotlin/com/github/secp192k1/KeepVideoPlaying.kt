package com.github.secp192k1

import android.content.Context
import android.view.View
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.after
import com.aliucord.patcher.before
import com.discord.widgets.chat.list.InlineMediaView
import com.discord.widgets.chat.list.WidgetChatList
import java.util.Collections
import java.util.WeakHashMap

@AliucordPlugin(requiresRestart = false)
@Suppress("unused")
class KeepVideoPlaying : Plugin() {
    private val retained = Collections.newSetFromMap(WeakHashMap<InlineMediaView, Boolean>())
    private val playerField = InlineMediaView::class.java.getDeclaredField("appMediaPlayer")
        .apply { isAccessible = true }

    private fun InlineMediaView.hasPlayer() = playerField.get(this) != null

    override fun start(context: Context) {
        patcher.before<InlineMediaView>(
            "onViewDetachedFromWindow",
            View::class.java,
        ) { param ->
            if (hasPlayer()) {
                retained.add(this)
                param.result = null
            }
        }

        patcher.before<InlineMediaView>("resetCurrentEmbed") { param ->
            if (hasPlayer()) param.result = null
        }

        patcher.before<InlineMediaView>("resetViews") {
            if (hasPlayer()) {
                retained.remove(this)
                onPause()
            }
        }

        patcher.after<WidgetChatList>("onPause") { releaseAll() }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        releaseAll()
    }

    private fun releaseAll() {
        retained.forEach(InlineMediaView::onPause)
        retained.clear()
    }
}

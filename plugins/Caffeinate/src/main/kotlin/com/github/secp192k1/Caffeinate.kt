package com.github.secp192k1

import android.content.Context
import android.view.WindowManager
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.*
import com.discord.player.AppMediaPlayer
import com.discord.widgets.media.WidgetMedia


@AliucordPlugin(requiresRestart = false)
@Suppress("unused")
class Caffeinate : Plugin() {
    private val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
    private val isVideo = WidgetMedia::class.java.getDeclaredMethod("isVideo").apply { isAccessible = true }

    override fun start(context: Context) {
        patcher.before<WidgetMedia>("handlePlayerEvent", AppMediaPlayer.Event::class.java) { param ->
            val window = Utils.appActivity.window
            when (param.args[0]) {
                is AppMediaPlayer.Event.f ->
                    if (isVideo.invoke(param.thisObject) as Boolean) window.addFlags(flag)
                is AppMediaPlayer.Event.d, is AppMediaPlayer.Event.e -> window.clearFlags(flag)
            }
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        Utils.appActivity.run { runOnUiThread { window.clearFlags(flag) } }
    }
}

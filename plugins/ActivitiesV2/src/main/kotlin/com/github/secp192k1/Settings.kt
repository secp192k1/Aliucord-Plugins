package com.github.secp192k1

import android.os.Bundle
import android.view.View
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.widgets.BottomSheet
import com.discord.views.CheckedSetting

internal class Settings(private val settings: SettingsAPI) : BottomSheet() {
    override fun onViewCreated(view: View, bundle: Bundle?) {
        super.onViewCreated(view, bundle)
        val ctx = view.context

        addView(
            Utils.createCheckedSetting(
                ctx,
                CheckedSetting.ViewType.SWITCH,
                "Prompt before granting permissions",
                "Ask before letting an activity use the microphone or camera, instead of granting silently",
            ).apply {
                isChecked = settings.getBool(Config.KEY_PERMISSION_PROMPT, true)
                setOnCheckedListener { settings.setBool(Config.KEY_PERMISSION_PROMPT, it) }
            },
        )

        addView(
            Utils.createCheckedSetting(
                ctx,
                CheckedSetting.ViewType.SWITCH,
                "WebView debugging",
                "Allow inspecting the activity WebView over chrome://inspect. Leave off unless developing",
            ).apply {
                isChecked = settings.getBool(Config.KEY_WEB_DEBUG, false)
                setOnCheckedListener { settings.setBool(Config.KEY_WEB_DEBUG, it) }
            },
        )
    }
}

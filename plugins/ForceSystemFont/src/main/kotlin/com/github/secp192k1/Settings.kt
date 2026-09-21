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
                "Apply to code blocks",
                "Replace monospace font as well",
            ).apply {
                isChecked = settings.getBool(Config.KEY_CODE_BLOCKS, false)
                setOnCheckedListener { settings.setBool(Config.KEY_CODE_BLOCKS, it) }
            },
        )
    }
}

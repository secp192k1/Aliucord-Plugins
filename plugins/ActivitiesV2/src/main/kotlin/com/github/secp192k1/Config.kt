package com.github.secp192k1

import com.aliucord.api.SettingsAPI

internal object Config {
    const val KEY_WEB_DEBUG = "webContentsDebugging"
    const val KEY_PERMISSION_PROMPT = "promptForPermissions"

    private var settings: SettingsAPI? = null

    fun attach(settings: SettingsAPI) {
        this.settings = settings
    }

    val webContentsDebugging get() = settings?.getBool(KEY_WEB_DEBUG, false) ?: false
    val promptForPermissions get() = settings?.getBool(KEY_PERMISSION_PROMPT, true) ?: true
}

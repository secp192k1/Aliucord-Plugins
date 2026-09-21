package com.github.secp192k1

import com.aliucord.api.SettingsAPI

internal object Config {
    const val KEY_CODE_BLOCKS = "applyToCodeBlocks"

    private var settings: SettingsAPI? = null

    fun attach(settings: SettingsAPI) {
        this.settings = settings
    }

    val applyToCodeBlocks get() = settings?.getBool(KEY_CODE_BLOCKS, false) ?: false
}

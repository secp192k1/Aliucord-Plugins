@file:Suppress("EnumValuesSoftDeprecate")

package com.github.secp192k1

import com.discord.utilities.search.query.node.answer.HasAnswerOption
import com.github.secp192k1.parsing.ScopeType

object HasAnswerOptionExtension {
    lateinit var values: Array<HasAnswerOption>

    fun scopeOf(option: Any?) =
        values.indexOfFirst { it === option }
            .takeIf { it >= 0 }?.let { ScopeType.values()[it] }
}

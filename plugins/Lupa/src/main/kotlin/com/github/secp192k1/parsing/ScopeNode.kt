@file:Suppress("EnumValuesSoftDeprecate")

package com.github.secp192k1.parsing

import com.discord.simpleast.core.parser.ParseSpec
import com.discord.utilities.search.network.SearchQuery
import com.discord.utilities.search.query.FilterType
import com.discord.utilities.search.query.node.answer.AnswerNode
import com.discord.utilities.search.query.node.filter.FilterNode
import com.discord.utilities.search.validation.SearchData
import com.github.secp192k1.FilterTypeExtension
import java.util.regex.Pattern

enum class ScopeType(val value: String) {
    CURRENT("current"),
    ALL("all");

    companion object {
        const val PARAM = "scope"

        fun from(value: String) = values().first { it.value == value }
    }
}

class ScopeNode(private val type: ScopeType) : AnswerNode() {
    companion object {
        private val filterPattern = Pattern.compile("^\\s*?(${ScopeType.PARAM}):", Pattern.UNICODE_CASE)
        private val scopePattern = Pattern.compile(
            ScopeType.values().joinToString("|", "^\\s*(", ")\\b") { it.value },
            Pattern.UNICODE_CASE,
        )

        fun filterNode() = FilterNode(FilterTypeExtension.SCOPE, ScopeType.PARAM)

        fun getFilterScopeRule(): ParserRule = RuleParser(filterPattern) { _, state ->
            ParseSpec(filterNode(), state)
        }

        fun getScopesRule(): ParserRule = RuleParser(scopePattern) { matcher, state ->
            ParseSpec(ScopeNode(ScopeType.from(matcher.group(1)!!)), state)
        }
    }

    override fun getValidFilters() = setOf(FilterTypeExtension.SCOPE)

    override fun isValid(searchData: SearchData?) = true

    override fun getText() = type.value

    override fun updateQuery(
        builder: SearchQuery.Builder,
        searchData: SearchData?,
        filterType: FilterType?,
    ) = builder.appendParam(ScopeType.PARAM, type.value)
}

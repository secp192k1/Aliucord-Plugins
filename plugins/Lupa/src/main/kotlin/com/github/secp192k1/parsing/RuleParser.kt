package com.github.secp192k1.parsing

import android.content.Context
import com.discord.simpleast.core.parser.ParseSpec
import com.discord.simpleast.core.parser.Parser
import com.discord.simpleast.core.parser.Rule
import com.discord.utilities.search.query.node.QueryNode
import java.util.regex.Matcher
import java.util.regex.Pattern

internal typealias ParserRule = Rule<Context, QueryNode, Any>

internal class RuleParser(
    regex: Pattern,
    private val parseMethod: (matcher: Matcher, state: Any?) -> ParseSpec<Context, Any?>,
) : ParserRule(regex) {
    override fun parse(
        matcher: Matcher,
        parser: Parser<Context, in QueryNode, in Any?>,
        obj: Any?,
    ): ParseSpec<Context, in Any?> = parseMethod(matcher, obj)
}

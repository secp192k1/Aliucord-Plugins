package com.github.secp192k1

// shoutout cilly for being the cutest and "helping" me make this
// https://github.com/cillynder/Awoocord/blob/main/plugins/Scout/src/main/kotlin/moe/lava/awoocord/scout/Scout.kt

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.after
import com.aliucord.patcher.before
import com.aliucord.patcher.component1
import com.aliucord.patcher.component2
import com.aliucord.patcher.component3
import com.aliucord.patcher.component4
import com.aliucord.patcher.component5
import com.aliucord.utils.GsonUtils
import com.aliucord.utils.GsonUtils.fromJson
import com.aliucord.utils.IOUtils
import com.aliucord.utils.RxUtils
import com.discord.models.domain.ModelSearchResponse
import com.discord.simpleast.core.parser.Parser
import com.discord.simpleast.core.parser.Rule
import com.discord.stores.StoreSearch
import com.discord.stores.StoreSearchInput
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.mg_recycler.SingleTypePayload
import com.discord.utilities.search.network.SearchFetcher
import com.discord.utilities.search.network.SearchQuery
import com.discord.utilities.search.query.FilterType
import com.discord.utilities.search.query.node.QueryNode
import com.discord.utilities.search.query.node.answer.HasAnswerOption
import com.discord.utilities.search.query.node.content.ContentNode
import com.discord.utilities.search.query.node.filter.FilterNode
import com.discord.utilities.search.query.parsing.QueryParser
import com.discord.utilities.search.strings.SearchStringProvider
import com.discord.utilities.search.suggestion.SearchSuggestionEngine
import com.discord.utilities.search.suggestion.entries.FilterSuggestion
import com.discord.utilities.search.suggestion.entries.HasSuggestion
import com.discord.utilities.search.suggestion.entries.SearchSuggestion
import com.discord.widgets.search.suggestions.WidgetSearchSuggestionsAdapter
import com.github.secp192k1.parsing.ScopeNode
import com.github.secp192k1.parsing.ScopeType
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.callbacks.XCallback
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

@AliucordPlugin(requiresRestart = false)
@Suppress("unused")
class Lupa : Plugin() {
    private val valuesField by lazy {
        FilterType::class.java.getDeclaredField($$"$VALUES").apply { isAccessible = true }
    }
    private val rulesField by lazy {
        Parser::class.java.getDeclaredField("rules").apply { isAccessible = true }
    }
    private val replaceAndPublish by lazy {
        StoreSearchInput::class.java.getDeclaredMethod(
            "replaceAndPublish",
            Int::class.javaPrimitiveType!!,
            List::class.java,
            List::class.java,
        ).apply { isAccessible = true }
    }

    private val filterTextId by lazy { Utils.getResId("suggestion_example_filter", "id") }
    private val answerTextId by lazy { Utils.getResId("suggestion_example_answer", "id") }
    private val placeholder by lazy { Utils.getResId("search_filter_from", "string") }
    private val allIcon by lazy { Utils.getResId("ic_globe_24dp", "drawable") }
    private val currentIcon by lazy { Utils.getResId("ic_text_channel_white_24dp", "drawable") }
    private val scalarKeys = arrayOf("content", "sort_by", "sort_order", "min_id", "max_id")

    @Suppress("EnumValuesSoftDeprecate")
    private val answerText = ScopeType.values().joinToString(" or ") { it.value }
    private val filterText = SpannableStringBuilder(ScopeType.PARAM).apply {
        setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        append(":")
    }

    private var origFilterTypes: Array<FilterType>? = null
    private var scopeAnswers = false

    @Volatile
    private var pagedQuery: SearchQuery? = null

    @Volatile
    private var offset = 0

    override fun start(context: Context) {
        extendFilterType()
        extendHasAnswerOption()
        patchQueryParser()
        patchSuggestion()
        patchAnswer()
        patchQuery()
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        pagedQuery = null
        valuesField.set(null, origFilterTypes ?: return logger.error("No unpatched filter types?", null))
        origFilterTypes = null
    }

    @Suppress("UNCHECKED_CAST")
    private fun extendFilterType() {
        val values = valuesField.get(null) as Array<FilterType>
        origFilterTypes = origFilterTypes ?: values

        FilterTypeExtension.SCOPE = FilterType::class.java.declaredConstructors[0]
            .apply { isAccessible = true }
            .newInstance("SCOPE", values.size) as FilterType

        valuesField.set(null, values + FilterTypeExtension.SCOPE)
    }

    @Suppress("EnumValuesSoftDeprecate")
    private fun extendHasAnswerOption() {
        val constructor = HasAnswerOption::class.java.declaredConstructors[0].apply { isAccessible = true }
        val next = HasAnswerOption.values().size
        HasAnswerOptionExtension.values = Array(ScopeType.values().size) {
            val scope = ScopeType.values()[it]
            constructor.newInstance(scope.name, next + it, scope.value) as HasAnswerOption
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun patchQueryParser() {
        patcher.after<QueryParser>(SearchStringProvider::class.java) {
            val rules = rulesField.get(this) as ArrayList<Rule<Context, QueryNode, Any>>
            rules.addAll(0, listOf(ScopeNode.getFilterScopeRule(), ScopeNode.getScopesRule()))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun patchSuggestion() {
        patcher.after<SearchSuggestionEngine>(
            "getFilterSuggestions",
            CharSequence::class.java,
            SearchStringProvider::class.java,
            Boolean::class.javaPrimitiveType!!,
        ) { (param, content: CharSequence) ->
            if (!"${ScopeType.PARAM}:".contains(content)) return@after

            val stock = origFilterTypes
            val final = (param.result as List<SearchSuggestion>).toMutableList()
            val at = if (stock == null) {
                final.size
            } else {
                final.indexOfLast { it is FilterSuggestion && it.filterType in stock } + 1
            }

            final.add(at, FilterSuggestion(FilterTypeExtension.SCOPE))
            param.result = final
        }

        for (method in arrayOf("getFilterText", "getAnswerText")) {
            patcher.before<WidgetSearchSuggestionsAdapter.FilterViewHolder>(
                method,
                FilterType::class.java,
            ) { (param, type: FilterType) ->
                if (type === FilterTypeExtension.SCOPE) param.result = placeholder
            }
        }

        patcher.before<WidgetSearchSuggestionsAdapter.FilterViewHolder>(
            "getIconDrawable",
            Context::class.java,
            FilterType::class.java,
        ) { (param, context: Context, type: FilterType) ->
            if (type !== FilterTypeExtension.SCOPE) return@before

            param.result = ContextCompat.getDrawable(context, allIcon)
        }

        patcher.after<WidgetSearchSuggestionsAdapter.FilterViewHolder>(
            "onConfigure",
            Int::class.javaPrimitiveType!!,
            MGRecyclerDataPayload::class.java,
        ) { (_, _: Int, payload: SingleTypePayload<*>) ->
            val data = payload.data
            if (data !is FilterSuggestion || data.filterType !== FilterTypeExtension.SCOPE) return@after

            itemView.findViewById<TextView>(filterTextId)?.apply { text = filterText }
            itemView.findViewById<TextView>(answerTextId)?.apply { text = answerText }
        }

        patcher.before<StoreSearchInput>(
            "onFilterClicked",
            FilterType::class.java,
            SearchStringProvider::class.java,
            List::class.java,
        ) { (param, type: FilterType, _: SearchStringProvider, query: List<QueryNode>) ->
            if (type !== FilterTypeExtension.SCOPE) return@before

            val index = when {
                query.isEmpty() -> 0
                query.last() is ContentNode -> query.lastIndex
                else -> query.size
            }

            replaceAndPublish.invoke(this, index, listOf(ScopeNode.filterNode()), query)

            param.result = null
        }
    }

    @SuppressLint("SetTextI18n")
    @Suppress("UNCHECKED_CAST", "EnumValuesSoftDeprecate")
    private fun patchAnswer() {
        patcher.patch(
            SearchSuggestionEngine::class.java,
            "getHasSuggestions",
            arrayOf(
                CharSequence::class.java,
                FilterType::class.java,
                SearchStringProvider::class.java,
            ),
            object : XC_MethodHook(PRIORITY_HIGHEST) {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val (_, content: CharSequence, filter: FilterType?) = param
                    scopeAnswers = filter === FilterTypeExtension.SCOPE

                    if (!scopeAnswers) return

                    param.result = ScopeType.values()
                        .filter { it.value.contains(content) }
                        .map { HasSuggestion(HasAnswerOptionExtension.values[it.ordinal]) }
                }
            },
        )

        patcher.before<HasAnswerOption>(
            "getLocalizedInputText",
            SearchStringProvider::class.java,
        ) { param ->
            val scope = HasAnswerOptionExtension.scopeOf(this) ?: return@before

            param.result = scope.value
        }

        patcher.before<WidgetSearchSuggestionsAdapter.HasViewHolder.Companion>(
            "getIconRes",
            HasAnswerOption::class.java,
        ) { (param, option: HasAnswerOption) ->
            val scope = HasAnswerOptionExtension.scopeOf(option) ?: return@before

            param.result = if (scope == ScopeType.ALL) allIcon else currentIcon
        }

        patcher.after<WidgetSearchSuggestionsAdapter.HeaderViewHolder>(
            "onConfigure",
            Int::class.javaPrimitiveType!!,
            MGRecyclerDataPayload::class.java,
        ) { (_, _: Int, payload: SingleTypePayload<*>) ->
            if (!scopeAnswers) return@after
            if (payload.data !== SearchSuggestion.Category.HAS) return@after

            (itemView as TextView).text = "Search Scope"
        }

        patcher.before<StoreSearchInput>(
            "onHasClicked",
            HasAnswerOption::class.java,
            CharSequence::class.java,
            CharSequence::class.java,
            List::class.java,
        ) { (param, option: HasAnswerOption, _: CharSequence, _: CharSequence, query: List<QueryNode>) ->
            val scope = HasAnswerOptionExtension.scopeOf(option) ?: return@before
            val nodes = listOf(ScopeNode.filterNode(), ScopeNode(scope))

            replaceAndPublish.invoke(this, answerStart(query), nodes, query)
            param.result = null
        }
    }

    private fun answerStart(query: List<QueryNode>): Int {
        if (query.size <= 1) return 0

        val last = query.lastIndex

        return when {
            query[last] is FilterNode -> last
            query[last] is ContentNode && query[last - 1] is FilterNode -> last - 1
            else -> -1
        }
    }

    private fun patchQuery() {
        patcher.before<SearchFetcher>(
            "makeQuery",
            StoreSearch.SearchTarget::class.java,
            Long::class.javaObjectType,
            SearchQuery::class.java,
        ) { (param, _: StoreSearch.SearchTarget, _: Long?, query: SearchQuery) ->
            val params = query.params
            if (params[ScopeType.PARAM]?.firstOrNull() != ScopeType.ALL.value) return@before

            if (query === pagedQuery) offset += 25 else offset = 0
            pagedQuery = query
            val page = offset

            param.result = RxUtils.create { subscriber ->
                Utils.threadPool.execute {
                    try {
                        subscriber.onNext(searchAllDms(params, page, query.includeNsfw))
                        subscriber.onCompleted()
                    } catch (t: Throwable) {
                        logger.error("Failed to search all dms", t)
                        subscriber.onError(t)
                    }
                }
            }
        }
    }

    private fun searchAllDms(params: Map<String, List<String>>, offset: Int, includeNsfw: Boolean): ModelSearchResponse {
        val messages = JSONObject()
            .put("sort_by", "timestamp")
            .put("sort_order", "desc")
            .put("offset", offset)
            .put("limit", 25)
            .put("include_nsfw", includeNsfw)

        for ((key, value) in params) {
            if (key != ScopeType.PARAM) {
                messages.put(key, if (key in scalarKeys) value[0] else JSONArray(value))
            }
        }

        val request = Http.Request.newDiscordRNRequest("/users/@me/messages/search/tabs", "POST")
            .setHeader("Content-Type", "application/json")

        val res = request.executeWithBody(
            JSONObject()
                .put("tabs", JSONObject().put("messages", messages))
                .put("track_exact_total_hits", true)
                .toString(),
        )

        if (!res.ok()) {
            val error = request.conn.errorStream?.let { IOUtils.readAsText(it) }.orEmpty()
            logger.error("searchAllDms ${res.statusCode} ${res.statusMessage} body=$error", null)
            throw IOException("${res.statusCode} ${res.statusMessage} $error")
        }

        return GsonUtils.gsonRestApi.fromJson(
            JSONObject(res.text()).getJSONObject("tabs").getJSONObject("messages").toString(),
            ModelSearchResponse::class.java,
        )
    }
}

package com.github.secp192k1

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import com.aliucord.Constants
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.GatewayAPI
import com.aliucord.entities.Plugin
import com.aliucord.patcher.after
import com.aliucord.patcher.component1
import com.aliucord.patcher.component2
import com.aliucord.patcher.component3
import com.aliucord.utils.DimenUtils.dp
import com.aliucord.wrappers.ChannelWrapper.Companion.guildId
import com.aliucord.wrappers.ChannelWrapper.Companion.id
import com.aliucord.wrappers.ChannelWrapper.Companion.type
import com.discord.api.channel.Channel
import com.discord.stores.StoreStream
import com.discord.stores.StoreVoiceChannelSelected
import com.discord.utilities.color.ColorCompat
import com.discord.widgets.channels.list.WidgetChannelsListAdapter
import com.discord.widgets.channels.list.items.ChannelListItem
import com.discord.widgets.channels.list.items.ChannelListItemStageVoiceChannel
import com.discord.widgets.channels.list.items.ChannelListItemVoiceChannel
import com.github.secp192k1.CallTimers.callStartTimes
import com.github.secp192k1.CallTimers.callTimersLines
import com.github.secp192k1.CallTimers.requestChannelInfo
import com.github.secp192k1.model.ChannelInfo
import com.github.secp192k1.model.VoiceStartTime
import com.lytefast.flexinput.R
import rx.Subscription
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.set

@AliucordPlugin(requiresRestart = false)
@Suppress("unused")
class TotalCallTime : Plugin() {
    private val callTimerId = View.generateViewId()
    private val callTimerViews = Collections.synchronizedMap(WeakHashMap<TextView, Long>())
    private val ticking = AtomicBoolean(false)
    private var listening = false
    private var guildSubscription: Subscription? = null

    override fun start(context: Context) {
        patchCallStartTime()
        patchChannelList()
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        guildSubscription?.unsubscribe()
        guildSubscription = null
        CallTimers.gatewaySocket = null
    }

    private fun patchCallStartTime() {
        GatewayAPI.onEvent<VoiceStartTime>("VOICE_CHANNEL_START_TIME_UPDATE") { update ->
            if (update.id == null) return@onEvent

            trackCallStart(update.id, update.voiceStartTime)
            tickCallTimers()
        }

        GatewayAPI.onEvent<ChannelInfo>("CHANNEL_INFO") { info ->
            info.channels?.forEach { entry ->
                val id = entry.id ?: return@forEach

                trackCallStart(id, entry.voiceStartTime)
            }

            tickCallTimers()
        }

        patcher.after<StoreVoiceChannelSelected>(
            "selectVoiceChannelInternal",
            Long::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!,
        ) { (param, channelId: Long) ->
            if (channelId <= 0L || param.result != StoreVoiceChannelSelected.JoinVoiceChannelResult.SUCCESS) return@after
            val guildId = StoreStream.getChannels().getChannel(channelId)?.guildId ?: return@after

            requestChannelInfo(guildId)
        }
    }

    private fun patchChannelList() {
        patcher.after<WidgetChannelsListAdapter.ItemChannelVoice>(
            "onConfigure",
            Int::class.javaPrimitiveType!!,
            ChannelListItem::class.java,
        ) { (_, _: Int, data: ChannelListItem) ->
            val channel = (data as? ChannelListItemVoiceChannel)?.channel ?: return@after

            bindCallTimer(itemView, channel)
        }

        patcher.after<WidgetChannelsListAdapter.ItemChannelStageVoice>(
            "onConfigure",
            Int::class.javaPrimitiveType!!,
            ChannelListItem::class.java,
        ) { (_, _: Int, data: ChannelListItem) ->
            val channel = (data as? ChannelListItemStageVoiceChannel)?.channel ?: return@after

            bindCallTimer(itemView, channel)
        }
    }

    private fun bindCallTimer(itemView: View, channel: Channel) {
        val root = itemView as? ViewGroup ?: return
        var timer = root.findViewById<TextView>(callTimerId)

        if (timer == null) {
            timer = TextView(root.context).apply {
                id = callTimerId
                includeFontPadding = false
                textSize = 12f
                setTextColor(ColorCompat.getThemedColor(context, R.b.colorTextPositive))
                typeface = ResourcesCompat.getFont(context, Constants.Fonts.sourcecodepro_semibold)
            }

            when (channel.type) {
                Channel.GUILD_VOICE -> {
                    val parent = root.findViewById<View>(
                        Utils.getResId("channels_item_voice_channel_name", "id")
                    )?.parent as? View ?: return

                    root.addView(timer, ConstraintLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                        endToStart = Utils.getResId("channels_item_voice_channel_guild_role_subscription_icon", "id")
                        topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                        bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                        marginEnd = 8.dp
                        goneEndMargin = 8.dp
                    })

                    (parent.layoutParams as? ConstraintLayout.LayoutParams)?.apply {
                        endToStart = callTimerId
                        marginEnd = 8.dp
                        goneEndMargin = 16.dp
                    }
                }
                Channel.GUILD_STAGE_VOICE -> {
                    val parent = root.findViewById<View>(
                        Utils.getResId("stage_channel_item_voice_channel_name", "id")
                    )?.parent as? View ?: return

                    root.addView(timer, RelativeLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                        addRule(RelativeLayout.ALIGN_PARENT_END)
                        addRule(RelativeLayout.CENTER_VERTICAL)
                        marginEnd = 16.dp
                    })

                    (parent.layoutParams as? RelativeLayout.LayoutParams)?.apply {
                        addRule(RelativeLayout.START_OF, callTimerId)
                        marginEnd = 8.dp
                    }
                }
                else -> return
            }
        }

        val start = callStartTimes[channel.id]
        callTimerViews[timer] = channel.id

        timer.apply {
            text = start?.let { callTimersLines(it).first() }
            visibility = if (start == null) View.GONE else View.VISIBLE
        }

        if (start != null) tickCallTimers()
    }

    fun tickCallTimers() {
        if (ticking.compareAndSet(false, true)) {
            Utils.mainThread.post(callTicker)
        }
    }

    private val callTicker = object : Runnable {
        override fun run() {
            runCatching {
                var live = false

                synchronized(callTimerViews) {
                    callTimerViews.forEach { (timer, channelId) ->
                        val start = callStartTimes[channelId]

                        if (start == null) {
                            timer.visibility = View.GONE
                            return@forEach
                        }

                        timer.text = callTimersLines(start).first()
                        timer.visibility = View.VISIBLE
                        live = true
                    }
                }

                ticking.set(live)
                if (live) Utils.mainThread.postDelayed(this, 1000L - System.currentTimeMillis() % 1000)
            }.onFailure {
                ticking.set(false)
                logger.error("Failed to update call timer text", it)
            }
        }
    }

    internal fun trackCallStart(channelId: Long, startTimeSec: Long?) {
        if (startTimeSec == null) callStartTimes.remove(channelId)
        else callStartTimes[channelId] = startTimeSec * 1000
    }
}

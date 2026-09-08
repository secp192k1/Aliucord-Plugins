package com.github.secp192k1

import com.aliucord.PluginManager.logger
import com.discord.gateway.GatewaySocket
import com.discord.gateway.io.Outgoing
import com.github.secp192k1.model.ChannelInfoRequest
import java.util.Collections
import java.util.Locale
internal object CallTimers {
    internal val callStartTimes = Collections.synchronizedMap(HashMap<Long, Long>())
    @Volatile
    internal var gatewaySocket: GatewaySocket? = null

    internal fun requestChannelInfo(guildId: Long) {
        if (guildId == 0L) logger.warn("requestChannelInfo(0): guild-only per docs, sending anyway as test")

        val socket = gatewaySocket ?: run {
            logger.warn("requestChannelInfo($guildId): no gateway socket captured yet")
            return
        }

        logger.debug("Requesting channel info for guild $guildId")

        // OPCODE_REQUEST_CHANNEL_INFO = 43
        GatewaySocket.`send$default`(
            socket,
            Outgoing(43, ChannelInfoRequest(guildId, listOf("status", "voice_start_time"))),
            false, null, 6, null,
        )
    }

    internal fun callTimersLines(start: Long): List<String> {
        val now = System.currentTimeMillis()
        val secs = ((now - start) / 1000).coerceAtLeast(0)

        val (days, hours, minutes, seconds) = listOf(secs / 86400, secs / 3600 % 24, secs / 60 % 60, secs % 60)
        val mmss = String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)

        val elapsed = when {
            days > 0 -> "$days:${"%02d".format(hours)}:$mmss"
            hours > 0 -> "$hours:$mmss"
            else -> mmss
        }

        return listOf(
            elapsed,
            // DateFormat.getTimeInstance().format(Date(start)),
            // DateFormat.getDateInstance().format(Date(start)),
            // DateUtils.getRelativeTimeSpanString(start, now, DateUtils.SECOND_IN_MILLIS),
        )
    }
}

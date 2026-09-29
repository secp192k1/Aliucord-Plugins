package com.github.secp192k1

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.util.LruCache
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aliucord.Http
import com.aliucord.Logger
import com.aliucord.Utils
import com.aliucord.api.PatcherAPI
import com.aliucord.patcher.after
import com.aliucord.patcher.before
import com.aliucord.patcher.component1
import com.aliucord.patcher.component2
import com.aliucord.utils.DimenUtils.dp
import com.aliucord.wrappers.ChannelWrapper.Companion.guildId
import com.aliucord.wrappers.ChannelWrapper.Companion.id
import com.aliucord.wrappers.ChannelWrapper.Companion.name
import com.aliucord.wrappers.ChannelWrapper.Companion.type
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.widgets.chat.input.WidgetChatInputAttachments
import com.discord.widgets.voice.fullscreen.CallParticipant
import com.discord.widgets.voice.fullscreen.WidgetCallFullscreen
import com.discord.widgets.voice.fullscreen.WidgetCallFullscreenViewModel
import com.discord.widgets.voice.fullscreen.grid.VideoCallGridViewHolder
import com.discord.widgets.chat.input.`WidgetChatInputAttachments$configureFlexInputContentPages$1`
import com.discord.widgets.chat.input.`WidgetChatInputAttachments$configureFlexInputContentPages$1$page$1`
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.tabs.TabLayout
import com.lytefast.flexinput.R
import com.lytefast.flexinput.fragment.FlexInputFragment
import de.robv.android.xposed.XC_MethodHook
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

internal object ActivityPicker {
    private val logger = Logger("ActivitiesV2")
    private val iconCache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val entriesCache = ConcurrentHashMap<Long, CachedEntries>()

    private const val TAB_TAG = "activities"
    private const val CACHE_TTL_MS = 5 * 60_000L

    private class ActivityEntry(val id: String, val name: String, val icon: String?)

    private class CachedEntries(val entries: List<ActivityEntry>, val fetchedAt: Long)

    fun patch(patcher: PatcherAPI) {
        patcher.patch(
            `WidgetChatInputAttachments$configureFlexInputContentPages$1`::class.java.getDeclaredMethod("invoke"),
            object : XC_MethodHook(PRIORITY_HIGHEST) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        WidgetChatInputAttachments.`access$getFlexInputFragment$p`(
                            (param.thisObject as `WidgetChatInputAttachments$configureFlexInputContentPages$1`).`this$0`
                        ).let { fragment ->
                            fragment.r += `WidgetChatInputAttachments$configureFlexInputContentPages$1$page$1`(
                                fragment.requireContext(),
                                R.e.ic_controller_24dp,
                                R.h.activity
                            )
                            logger.info("Added activities page to attachments, pages=${fragment.r.size}")
                        }
                    } catch (e: Throwable) {
                        logger.error("Failed to add activities tab", e)
                    }
                }
            }
        )

        patcher.after<b.b.a.a.`a$e`>("invoke") {
            val tabLayout = `this$0`.l ?: return@after
            val pages = (`$flexInputFragment` as FlexInputFragment).r
            val offset = tabLayout.tabCount - pages.size

            pages.forEachIndexed { index, page ->
                if (page.icon == R.e.ic_controller_24dp && page.contentDesc == R.h.activity) {
                    val tab = tabLayout.getTabAt(offset + index)
                    if (tab == null) {
                        logger.warn("No tab at ${offset + index} for activities page, tabs=${tabLayout.tabCount}")
                    }
                    tab?.setTag(TAB_TAG)?.setContentDescription("Activities")
                }
            }
        }

        patcher.after<TabLayout.Tab>(
            "setIcon",
            Int::class.javaPrimitiveType!!
        ) { (_, id: Int) ->
            if (id == R.e.ic_controller_24dp) {
                val color = ColorCompat.getThemedColor(view, R.b.flexInputIconColor)
                icon?.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_ATOP)
            }
        }

        patcher.before<b.b.a.a.c>(
            "onPageSelected",
            Int::class.javaPrimitiveType!!
        ) { (param, index: Int) ->
            val tabLayout = this.a.l
            val tab = tabLayout.getTabAt(index)

            if (tab?.tag == TAB_TAG && tabLayout.selectedTabPosition == 0) {
                logger.info("Swallowed page selection of activities tab")
                val parentFragment = this.a.parentFragment as FlexInputFragment
                parentFragment.s.onContentDialogDismissed(false)
                param.result = null
            }
        }

        patcher.before<b.b.a.a.b>(
            "onTabSelected",
            TabLayout.Tab::class.java
        ) { (param, tab: TabLayout.Tab) ->
            if (tab.tag != TAB_TAG) return@before

            logger.info("Activities tab selected")
            val parentFragment = this.a.parentFragment as FlexInputFragment
            parentFragment.s.onContentDialogDismissed(false)
            param.result = null
            openPicker()
        }

        patcher.before<WidgetCallFullscreen>(
            "handleEvent",
            WidgetCallFullscreenViewModel.Event::class.java
        ) { (param, event: WidgetCallFullscreenViewModel.Event) ->
            if (event !is WidgetCallFullscreenViewModel.Event.ShowActivitiesDesktopOnlyDialog) return@before
            logger.info("Replacing desktop-only activities dialog with voice activity join")
            param.result = null
            joinVoiceActivity()
        }

        // the subtext saying "Coming soon to mobile"
        patcher.after<VideoCallGridViewHolder.EmbeddedActivity>(
            "configure",
            CallParticipant.EmbeddedActivityParticipant::class.java,
            Function1::class.java
        ) { _ ->
            replaceComingSoonText(binding.a)
        }
    }

    private fun replaceComingSoonText(root: ViewGroup) {
        replaceComingSoonText(
            root,
            root.context.getString(R.h.embedded_activities_in_video_call_mobile_preview_subtitle_short),
            root.context.getString(R.h.discord_u_coming_soon_to_mobile)
        )
    }

    private fun replaceComingSoonText(root: ViewGroup, comingSoon: String, comingSoonAlt: String) {
        for (i in 0 until root.childCount) {
            when (val child = root.getChildAt(i)) {
                is TextView -> if (child.text == comingSoon || child.text == comingSoonAlt) child.text = "Tap to join"
                is ViewGroup -> replaceComingSoonText(child, comingSoon, comingSoonAlt)
            }
        }
    }

    private fun joinVoiceActivity() {
        val channelId = StoreStream.getVoiceChannelSelected().selectedVoiceChannelId
        if (channelId <= 0L) return logger.warn("No voice channel selected, cannot join activity")

        val stores = StoreStream.`access$getCollector$cp`().value as StoreStream
        val running = stores.`getEmbeddedActivities$app_productionGoogleRelease`().embeddedActivities[channelId]?.values
        val activity = running?.firstOrNull()

        if (activity == null) {
            logger.warn("No activity running in voice channel $channelId")
            Utils.showToast("No activity running in this channel")
            return
        }

        val name = activity.name ?: "Activity"
        logger.info("Joining $name (${activity.applicationId}) in voice channel $channelId, running=${running.size}")
        ActivityApi.launch(channelId, activity.guildId, activity.applicationId.toString(), name, voice = true) { reason ->
            Utils.showToast(if (reason != null) "Failed to join $name: $reason" else "Failed to join $name")
        }
    }

    private fun openPicker() {
        val channel = StoreStream.getChannelsSelected().selectedChannel
            ?: StoreStream.getVoiceChannelSelected().selectedVoiceChannel
        val guildId = channel.guildId
        val channelId = channel.id
        val voice = ChannelType.from(channel.type)?.isVoice ?: false

        logger.info("Opening picker for channel name=${channel.name} id=$channelId guild=$guildId type=${channel.type} isVoice=$voice")

        if (channelId == 0L) {
            logger.warn("No channel selected, not opening picker")
            Utils.showToast("No channel selected")
            return
        }

        entriesCache[guildId]?.takeIf { System.currentTimeMillis() - it.fetchedAt < CACHE_TTL_MS }?.let {
            logger.info("Using cached activities of guild $guildId: ${it.entries.size} entries")
            showGrid(channelId, guildId, voice, it.entries)
            return
        }

        logger.info("Fetching activities of guild $guildId")
        Utils.threadPool.execute {
            val entries = fetchEntries(guildId)
            if (entries != null) entriesCache[guildId] = CachedEntries(entries, System.currentTimeMillis())

            val toShow = entries ?: entriesCache[guildId]?.entries
            if (entries == null && toShow != null) {
                logger.warn("Fetch failed, showing ${toShow.size} stale cached activities of guild $guildId")
            }
            Utils.mainThread.post {
                when {
                    toShow == null -> {
                        logger.warn("Fetch failed and nothing cached for guild $guildId")
                        Utils.showToast("Failed to load activities")
                    }
                    toShow.isEmpty() -> {
                        logger.warn("No activities available in guild $guildId")
                        Utils.showToast("No activities available")
                    }
                    else -> showGrid(channelId, guildId, voice, toShow)
                }
            }
        }
    }

    private fun fetchEntries(guildId: Long): List<ActivityEntry>? {
        val guildEntries = mutableListOf<ActivityEntry>()
        val shelfEntries = mutableListOf<ActivityEntry>()
        val entries = mutableListOf<ActivityEntry>()
        val seen = HashSet<String>()

        val guildFuture = if (guildId != 0L) {
            Utils.threadPool.submit<Boolean> { fetchGuildApps(guildId, guildEntries, HashSet()) }
        } else null

        val isShelfValid = fetchShelf(guildId, shelfEntries, HashSet())

        val isGuildValid = try {
            guildFuture?.get() == true
        } catch (e: Throwable) {
            logger.error("Failed to fetch guild activities", e)
            false
        }

        if (!isGuildValid && !isShelfValid) {
            logger.warn("Guild application index and activity shelf both failed for guild $guildId")
            return null
        }

        for (entry in guildEntries) if (seen.add(entry.id)) entries.add(entry)
        for (entry in shelfEntries) if (seen.add(entry.id)) entries.add(entry)

        logger.info("Activities of guild $guildId: guild=${guildEntries.size} (ok=$isGuildValid) shelf=${shelfEntries.size} (ok=$isShelfValid) merged=${entries.size}")
        return entries
    }

    private fun fetchGuildApps(guildId: Long, entries: MutableList<ActivityEntry>, seen: MutableSet<String>): Boolean {
        try {
            val res = Http.Request.newDiscordRNRequest("/guilds/$guildId/application-command-index").execute()
            if (!res.ok()) {
                logger.error("application-command-index failed: ${res.statusCode} ${res.statusMessage}", null)
                return false
            }

            val apps = JSONObject(res.text()).optJSONArray("applications") ?: run {
                logger.warn("Application index of guild $guildId has no applications")
                return true
            }
            for (i in 0 until apps.length()) {
                val app = apps.getJSONObject(i)
                if (!app.has("embedded_activity_config")) continue
                val entry = entryFrom(app)
                if (seen.add(entry.id)) entries.add(entry)
            }

            logger.info("Guild $guildId: ${entries.size} activities among ${apps.length()} applications")
            return true
        } catch (e: Throwable) {
            logger.error("Failed to fetch guild activities", e)
            return false
        }
    }

    private fun fetchShelf(guildId: Long, entries: MutableList<ActivityEntry>, seen: MutableSet<String>): Boolean {
        try {
            val query = if (guildId != 0L) "?guild_id=$guildId" else ""
            val res = Http.Request.newDiscordRNRequest("/activities/shelf$query").execute()
            if (!res.ok()) {
                logger.error("activities/shelf failed: ${res.statusCode} ${res.statusMessage}", null)
                return false
            }

            val shelf = JSONObject(res.text())
            val appsById = HashMap<String, JSONObject>()
            val apps = shelf.optJSONArray("applications")

            if (apps != null) for (i in 0 until apps.length()) {
                val app = apps.getJSONObject(i)
                appsById[app.getString("id")] = app
            }

            // Ordered by "shelf_rank"
            val activities = shelf.optJSONArray("activities")
            if (activities != null) for (i in 0 until activities.length()) {
                val appId = activities.getJSONObject(i).optString("application_id")
                val app = appsById[appId]
                if (app == null) {
                    logger.warn("Shelf activity $appId has no matching application, skipped")
                    continue
                }
                if (seen.add(appId)) entries.add(entryFrom(app))
            }

            logger.info("Activity shelf of guild $guildId: ${entries.size} of ${activities?.length() ?: 0} activities, ${appsById.size} applications")
            return true
        } catch (e: Throwable) {
            logger.error("Failed to fetch activities shelf", e)
            return false
        }
    }

    private fun entryFrom(app: JSONObject) = ActivityEntry(
        app.getString("id"),
        app.optString("name"),
        app.optString("icon").takeIf { it.isNotEmpty() && it != "null" }
    )

    private fun loadIcon(entry: ActivityEntry, image: ImageView) {
        val key = "${entry.id}/${entry.icon ?: return}"
        iconCache[key]?.let {
            image.setImageBitmap(it)
            return
        }

        Utils.threadPool.execute {
            try {
                val bitmap = URL("https://cdn.discordapp.com/app-icons/$key.png?size=128")
                    .openStream().use(BitmapFactory::decodeStream)
                    ?: return@execute logger.warn("Failed to decode icon of ${entry.id}")

                iconCache.put(key, bitmap)
                image.post { image.setImageBitmap(bitmap) }
            } catch (e: Throwable) {
                logger.error("Failed to load icon for ${entry.id}", e)
            }
        }
    }

    private fun showGrid(channelId: Long, guildId: Long, voice: Boolean, entries: List<ActivityEntry>) {
        val activity = EmbeddedActivityHost.hostActivity() ?: return logger.warn("No host activity, cannot show activity picker")
        logger.info("Showing activity picker: ${entries.size} entries channel=$channelId guild=$guildId voice=$voice")

        val bg = ColorCompat.getThemedColor(activity, R.b.colorBackgroundPrimary)
        val textColor = ColorCompat.getThemedColor(activity, R.b.colorHeaderPrimary)
        val dialog = BottomSheetDialog(activity)

        val grid = GridLayout(activity).apply {
            columnCount = 3
            setPadding(8.dp, 16.dp, 8.dp, 16.dp)
        }

        for (entry in entries) {
            val cell = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(8.dp, 12.dp, 8.dp, 12.dp)
                setOnClickListener {
                    logger.info("Picked ${entry.name} (${entry.id})")
                    dialog.dismiss()
                    ActivityApi.launch(channelId, guildId, entry.id, entry.name, voice) { reason ->
                        Utils.showToast(if (reason != null) "Failed to launch ${entry.name}: $reason" else "Failed to launch ${entry.name}")
                    }
                }
            }
            val image = ImageView(activity)
            cell.addView(image, LinearLayout.LayoutParams(56.dp, 56.dp))

            TextView(activity).also {
                it.text = entry.name
                it.setTextColor(textColor)
                it.gravity = Gravity.CENTER
                it.maxLines = 2
                cell.addView(it, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 6.dp })
            }

            grid.addView(cell, GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            ).apply { width = 0 })

            loadIcon(entry, image)
        }

        val scroll = ScrollView(activity).apply {
            setBackgroundColor(bg)
            addView(grid, MATCH_PARENT, WRAP_CONTENT)
        }

        dialog.setContentView(scroll)
        dialog.show()
    }
}

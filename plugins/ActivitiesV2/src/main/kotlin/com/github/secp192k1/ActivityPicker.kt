package com.github.secp192k1

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.Gravity
import android.view.View
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
    private val tabStringId = View.generateViewId()
    private val iconCache = ConcurrentHashMap<String, Bitmap>()
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
                                tabStringId
                            )
                        }
                    } catch (e: Throwable) {
                        logger.error("Failed to add activities tab", e)
                    }
                }
            }
        )

        patcher.before<TabLayout.Tab>(
            "setContentDescription",
            Int::class.javaPrimitiveType!!
        ) { (param, id: Int) ->
            if (id == tabStringId) {
                tag = TAB_TAG
                param.result = setContentDescription("Activities")
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
        val comingSoon = root.context.getString(R.h.embedded_activities_in_video_call_mobile_preview_subtitle_short)
        val comingSoonAlt = root.context.getString(R.h.discord_u_coming_soon_to_mobile)
        for (i in 0 until root.childCount) {
            when (val child = root.getChildAt(i)) {
                is TextView -> if (child.text == comingSoon || child.text == comingSoonAlt) child.text = "Tap to join"
                is ViewGroup -> replaceComingSoonText(child)
            }
        }
    }

    private fun joinVoiceActivity() {
        val channelId = StoreStream.getVoiceChannelSelected().selectedVoiceChannelId
        if (channelId <= 0L) return

        val stores = StoreStream.`access$getCollector$cp`().value as StoreStream
        val activity = stores.`getEmbeddedActivities$app_productionGoogleRelease`()
            .embeddedActivities[channelId]?.values?.firstOrNull()

        if (activity == null) {
            Utils.showToast("No activity running in this channel")
            return
        }

        val name = activity.name ?: "Activity"
        ActivityApi.launch(channelId, activity.guildId, activity.applicationId.toString(), name, voice = true) {
            Utils.showToast("Failed to join $name")
        }
    }

    private fun openPicker() {
        val channel = StoreStream.getChannelsSelected().selectedChannel
            ?: StoreStream.getVoiceChannelSelected().selectedVoiceChannel
        val guildId = channel.guildId
        val channelId = channel.id
        val voice = ChannelType.from(channel.type)?.isVoice ?: false

        logger.info("Opening picker for channel name=${channel.name} type=${channel.type} isVoice=$voice")

        if (channelId == 0L) {
            Utils.showToast("No channel selected")
            return
        }

        entriesCache[guildId]?.takeIf { System.currentTimeMillis() - it.fetchedAt < CACHE_TTL_MS }?.let {
            showGrid(channelId, guildId, voice, it.entries)
            return
        }

        Utils.threadPool.execute {
            val entries = fetchEntries(guildId)
            if (entries != null) entriesCache[guildId] = CachedEntries(entries, System.currentTimeMillis())

            val toShow = entries ?: entriesCache[guildId]?.entries
            Utils.mainThread.post {
                when {
                    toShow == null -> Utils.showToast("Failed to load activities")
                    toShow.isEmpty() -> Utils.showToast("No activities available")
                    else -> showGrid(channelId, guildId, voice, toShow)
                }
            }
        }
    }

    private fun fetchEntries(guildId: Long): List<ActivityEntry>? {
        val entries = mutableListOf<ActivityEntry>()
        val seen = HashSet<String>()
        val isGuildValid = guildId != 0L && fetchGuildApps(guildId, entries, seen)
        val isShelfValid = fetchShelf(guildId, entries, seen)
        return if (isGuildValid || isShelfValid) entries else null
    }

    private fun fetchGuildApps(guildId: Long, entries: MutableList<ActivityEntry>, seen: MutableSet<String>): Boolean {
        try {
            val res = Http.Request.newDiscordRNRequest("/guilds/$guildId/application-command-index").execute()
            if (!res.ok()) {
                logger.error("application-command-index failed: ${res.statusCode} ${res.statusMessage}", null)
                return false
            }

            val apps = JSONObject(res.text()).optJSONArray("applications") ?: return true
            for (i in 0 until apps.length()) {
                val app = apps.getJSONObject(i)
                if (!app.has("embedded_activity_config")) continue
                val entry = entryFrom(app)
                if (seen.add(entry.id)) entries.add(entry)
            }

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
                val app = appsById[appId] ?: continue
                if (seen.add(appId)) entries.add(entryFrom(app))
            }

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
                    .openStream().use(BitmapFactory::decodeStream) ?: return@execute
                iconCache[key] = bitmap
                image.post { image.setImageBitmap(bitmap) }
            } catch (e: Throwable) {
                logger.error("Failed to load icon for ${entry.id}", e)
            }
        }
    }

    private fun showGrid(channelId: Long, guildId: Long, voice: Boolean, entries: List<ActivityEntry>) {
        val activity = EmbeddedActivityHost.hostActivity() ?: return

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
                    dialog.dismiss()
                    ActivityApi.launch(channelId, guildId, entry.id, entry.name, voice) {
                        Utils.showToast("Failed to launch ${entry.name}")
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

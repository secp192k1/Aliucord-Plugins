package com.github.secp192k1

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import com.aliucord.Logger
import com.aliucord.Utils
import com.aliucord.utils.DimenUtils.dp
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lytefast.flexinput.R
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

internal class ActivityDialog(val dialog: BottomSheetDialog, val participantsRow: LinearLayout)

internal object ActivityUi {
    private val logger = Logger("ActivitiesV2")
    private val avatarCache = ConcurrentHashMap<String, Bitmap>()

    private const val MAX_AVATARS = 5

    @SuppressLint("SetTextI18n")
    fun buildDialog(activity: Activity, content: View, onDismiss: () -> Unit): ActivityDialog {
        val bg = ColorCompat.getThemedColor(activity, R.b.colorBackgroundPrimary)
        val handle = ColorCompat.getThemedColor(activity, R.b.colorBackgroundModifierAccent)
        val leave = ColorCompat.getThemedColor(activity, R.b.colorButtonDangerBackground)
        val white = ContextCompat.getColor(activity, R.c.white)

        val screenHeight = activity.resources.displayMetrics.heightPixels
        val dialog = BottomSheetDialog(activity)

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        val header = FrameLayout(activity).apply { setPadding(0, 8.dp, 0, 8.dp) }
        View(activity).also {
            it.background = GradientDrawable().apply { setColor(handle); cornerRadius = 3.dp.toFloat() }
            header.addView(it, FrameLayout.LayoutParams(36.dp, 5.dp, Gravity.CENTER_HORIZONTAL).apply { topMargin = 6.dp })
        }

        val participantsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(
            participantsRow,
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL)
                .apply { marginStart = 12.dp }
        )

        TextView(activity).also {
            it.text = "Leave"
            it.setTextColor(white)
            it.typeface = Typeface.DEFAULT_BOLD
            it.background = GradientDrawable().apply { setColor(leave); cornerRadius = 16.dp.toFloat() }
            it.setPadding(16.dp, 8.dp, 16.dp, 8.dp)
            it.setOnClickListener { dialog.dismiss() }
            header.addView(it, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = 12.dp })
        }
        card.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        card.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        dialog.setContentView(card)
        dialog.setCancelable(false)
        dialog.behavior.apply {
            isHideable = false
            peekHeight = (screenHeight * 0.15f).toInt()
        }

        dialog.setOnShowListener {
            dialog.findViewById<View>(R.f.design_bottom_sheet)?.let { sheet ->
                sheet.setBackgroundColor(bg)
                sheet.layoutParams = sheet.layoutParams.apply { height = MATCH_PARENT }
            }
            card.layoutParams = card.layoutParams.apply { height = MATCH_PARENT }
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED

            val window = dialog.window
            window?.attributes?.dimAmount = 1f
            dialog.behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onStateChanged(bottomSheet: View, newState: Int) {}
                override fun onSlide(bottomSheet: View, slideOffset: Float) {
                    window?.attributes = window.attributes?.apply {
                        dimAmount = slideOffset.coerceIn(0f, 1f)
                    }
                }
            })
        }

        dialog.setOnDismissListener { onDismiss() }
        return ActivityDialog(dialog, participantsRow)
    }

    @SuppressLint("SetTextI18n")
    fun updateParticipants(row: LinearLayout, userIds: List<Long>) {
        row.removeAllViews()

        val users = StoreStream.getUsers().users
        for (id in userIds.take(MAX_AVATARS)) {
            val user = users[id] ?: continue
            val image = ImageView(row.context)
            image.contentDescription = user.username
            row.addView(image, LinearLayout.LayoutParams(24.dp, 24.dp).apply { marginEnd = 4.dp })
            loadAvatar(user.id, user.avatar, image)
        }

        val overflow = userIds.size - MAX_AVATARS
        if (overflow > 0) {
            TextView(row.context).also {
                it.text = "+$overflow"
                it.setTextColor(ColorCompat.getThemedColor(row.context, R.b.colorHeaderSecondary))
                it.textSize = 12f
                row.addView(it, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            }
        }
    }

    private fun loadAvatar(userId: Long, avatarHash: String?, image: ImageView) {
        val url = if (avatarHash != null) {
            "https://cdn.discordapp.com/avatars/$userId/$avatarHash.png?size=64"
        } else {
            "https://cdn.discordapp.com/embed/avatars/${(userId shr 22) % 6}.png"
        }

        avatarCache[url]?.let {
            setRoundAvatar(image, it)
            return
        }

        Utils.threadPool.execute {
            try {
                val bitmap = URL(url).openStream().use(BitmapFactory::decodeStream) ?: return@execute
                avatarCache[url] = bitmap
                image.post { setRoundAvatar(image, bitmap) }
            } catch (e: Throwable) {
                logger.error("Failed to load avatar for $userId", e)
            }
        }
    }

    private fun setRoundAvatar(image: ImageView, bitmap: Bitmap) {
        image.setImageDrawable(
            RoundedBitmapDrawableFactory.create(image.resources, bitmap).apply { isCircular = true }
        )
    }
}

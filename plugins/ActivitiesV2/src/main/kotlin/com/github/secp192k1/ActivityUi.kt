package com.github.secp192k1

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.aliucord.utils.DimenUtils.dp
import com.discord.utilities.color.ColorCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lytefast.flexinput.R

internal object ActivityUi {
    @SuppressLint("SetTextI18n")
    fun buildDialog(activity: Activity, content: View, onDismiss: () -> Unit): BottomSheetDialog {
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
        return dialog
    }
}

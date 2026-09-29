package com.github.secp192k1

import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import com.aliucord.Constants
import com.aliucord.Http.Request.newDiscordRNRequest
import com.aliucord.PluginManager.logger
import com.aliucord.Utils
import com.aliucord.utils.DimenUtils
import com.aliucord.utils.GsonUtils
import com.aliucord.views.Button
import com.aliucord.views.DangerButton
import com.discord.models.domain.ModelInvite
import com.discord.models.invite.InviteUtils
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.duration.DurationUtilsKt
import com.google.android.material.card.MaterialCardView
import com.lytefast.flexinput.R

class CodesPage : Fragment() {
    companion object {
        val stale = MutableLiveData(true)
    }

    private lateinit var list: LinearLayout
    private var invites = emptyArray<ModelInvite>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val ctx = inflater.context
        val padding = DimenUtils.defaultPadding

        list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, padding, padding)
        }

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                LinearLayout(ctx).apply {
                    setPadding(padding, padding / 2, padding, 0)
                    addView(
                        Button(ctx).apply {
                            setText(R.h.create_link)
                            setOnClickListener { refresh("POST") }
                        },
                        LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = padding / 2 },
                    )
                    addView(
                        DangerButton(ctx).apply {
                            setText(R.h.friend_invite_revoke_all)
                            setOnClickListener { refresh("DELETE") }
                        },
                        LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
                    )
                },
            )
            addView(
                NestedScrollView(ctx).apply {
                    isFillViewport = true
                    addView(list)
                },
                LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f),
            )
        }
    }

    override fun onViewCreated(view: View, state: Bundle?) = stale.observe(viewLifecycleOwner) { refresh() }

    private fun refresh(method: String? = null) {
        render(loading = true)
        Utils.threadPool.execute {
            try {
                if (method != null) request(method).assertOk()
                invites = request("GET").json(GsonUtils.gsonRestApi, Array<ModelInvite>::class.java)
            } catch (e: Throwable) {
                logger.error("Failed to refresh friend invites", e)
                Utils.showToast("Failed to update friend invites")
            }
            Utils.mainThread.post { render() }
        }
    }

    private fun render(loading: Boolean = false) {
        val ctx = list.context
        list.removeAllViews()
        list.gravity = if (loading || invites.isEmpty()) Gravity.CENTER else Gravity.TOP

        when {
            loading -> list.addView(ProgressBar(ctx), WRAP_CONTENT, WRAP_CONTENT)
            invites.isEmpty() -> list.addView(
                TextView(ctx, null, 0, R.i.UiKit_Settings_Item_SubText).apply {
                    setText(R.h.activity_feed_none_playing_header)
                    typeface = ResourcesCompat.getFont(context, Constants.Fonts.ginto_bold)
                },
                WRAP_CONTENT,
                WRAP_CONTENT,
            )
            else -> invites.forEach { list.addView(card(it)) }
        }
    }

    private fun card(invite: ModelInvite) = MaterialCardView(list.context).apply {
        val link = InviteUtils.INSTANCE.createLinkFromCode(invite.code, null)
        val expires = DurationUtilsKt.humanizeDuration(context, invite.timeToExpirationMillis)

        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = DimenUtils.defaultPadding / 2
        }
        radius = DimenUtils.defaultCardRadius.toFloat()
        setCardBackgroundColor(ColorCompat.getThemedColor(context, R.b.colorBackgroundSecondary))
        addView(
            TextView(context, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                text = SpannableStringBuilder(link).append("\nExpires in $expires · ${invite.uses} / ${invite.maxUses} uses", RelativeSizeSpan(0.8f), 0)
                setCompoundDrawablesRelativeWithIntrinsicBounds(Utils.tintToTheme(ContextCompat.getDrawable(context, R.e.ic_link_white_24dp)), null, null, null)
                setOnClickListener {
                    Utils.setClipboard(link, link)
                    Utils.showToast(context.getString(R.h.link_copied))
                }
            },
        )
    }

    private fun request(method: String) = newDiscordRNRequest("/users/@me/invites", method).run {
        if (method == "POST") executeWithJson(emptyMap<String, Any>()) else execute()
    }
}

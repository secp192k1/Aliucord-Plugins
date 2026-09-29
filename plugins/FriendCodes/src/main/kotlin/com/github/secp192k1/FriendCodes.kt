package com.github.secp192k1

import android.content.Context
import android.view.View
import androidx.fragment.app.FragmentFactory
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.PreHook
import com.aliucord.patcher.after
import com.aliucord.utils.ReflectUtils
import com.discord.models.domain.ModelUserRelationship
import com.discord.stores.StoreUserRelationships
import com.discord.utilities.simple_pager.SimplePager
import com.discord.widgets.friends.WidgetFriendsAdd
import com.lytefast.flexinput.R

@AliucordPlugin(requiresRestart = false)
@Suppress("unused")
class FriendCodes : Plugin() {
    override fun start(context: Context) {
        patcher.after<WidgetFriendsAdd>("onViewBound", View::class.java) {
            val pager = (it.args[0] as View).findViewById<SimplePager>(Utils.getResId("add_friend_view_pager", "id"))

            @Suppress("UNCHECKED_CAST")
            val items = ReflectUtils.getField(pager.adapter!!, "items") as List<SimplePager.Adapter.Item>
            pager.adapter = SimplePager.Adapter(
                parentFragmentManager,
                *items.toTypedArray(),
                SimplePager.Adapter.Item(getString(R.h.invites)) { CodesPage() },
            )
        }

        // Refresh count when link is used
        patcher.after<StoreUserRelationships>("handleRelationshipAdd", ModelUserRelationship::class.java) {
            if ((it.args[0] as ModelUserRelationship).type == ModelUserRelationship.TYPE_FRIEND) CodesPage.stale.postValue(true)
        }

        patcher.patch(
            FragmentFactory::class.java,
            "loadClass",
            arrayOf(ClassLoader::class.java, String::class.java),
            PreHook {
                if (it.args[1] == CodesPage::class.java.name) it.result = CodesPage::class.java
            },
        )
    }

    override fun stop(context: Context) = patcher.unpatchAll()
}

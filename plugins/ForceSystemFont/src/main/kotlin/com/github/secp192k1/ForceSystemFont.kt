package com.github.secp192k1

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.text.TextPaint
import android.text.style.TextAppearanceSpan
import android.util.TypedValue
import androidx.core.content.res.ResourcesCompat
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.after
import com.aliucord.patcher.component1
import com.aliucord.patcher.component2
import com.aliucord.patcher.component3
import de.robv.android.xposed.XC_MethodHook
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

@AliucordPlugin(requiresRestart = true)
@Suppress("unused")
class ForceSystemFont : Plugin() {
    private val cache = ConcurrentHashMap<Long, Typeface>()
    private val codeFonts = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
    private val appearanceFonts = ConcurrentHashMap<Int, Int>()
    private val spanFonts = Collections.synchronizedMap(WeakHashMap<TextAppearanceSpan, Int>())

    init {
        settingsTab = SettingsTab(Settings::class.java, SettingsTab.Type.BOTTOM_SHEET).withArgs(settings)
        Config.attach(settings)
    }

    @SuppressLint("RestrictedApi")
    override fun start(context: Context) {
        patcher.patch(
            ResourcesCompat::class.java.getDeclaredMethod(
                "loadFont",
                Context::class.java,
                Resources::class.java,
                TypedValue::class.java,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                ResourcesCompat.FontCallback::class.java,
                Handler::class.java,
                Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
            ),
            object : XC_MethodHook(PRIORITY_HIGHEST) {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val resources = param.args[1] as Resources
                    val id = param.args[3] as Int
                    val style = param.args[4] as Int

                    val font = cache.getOrPut((id.toLong() shl 2) or style.toLong()) {
                        systemFont(resources, id, style)
                    }

                    if (id in codeFonts && !Config.applyToCodeBlocks) return

                    param.result = font

                    val callback = param.args[5] as? ResourcesCompat.FontCallback?
                    callback?.callbackSuccessAsync(font, param.args[6] as Handler?)
                }
            },
        )

        patcher.after<TextAppearanceSpan>(
            Context::class.java,
            Int::class.javaPrimitiveType!!,
        ) { (_, context: Context, appearance: Int) ->
            val fontId = appearanceFont(context, appearance)
            if (fontId != 0) spanFonts[this] = fontId
        }

        for (method in arrayOf("updateMeasureState", "updateDrawState")) {
            patcher.after<TextAppearanceSpan>(method, TextPaint::class.java) { (_, paint: TextPaint) ->
                val fontId = spanFonts[this] ?: return@after
                val style = paint.typeface?.style ?: Typeface.NORMAL

                val font = cache.getOrPut((fontId.toLong() shl 2) or style.toLong()) {
                    systemFont(Utils.appContext.resources, fontId, style)
                }

                if (fontId in codeFonts && !Config.applyToCodeBlocks) return@after

                paint.typeface = font
            }
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        cache.clear()
        codeFonts.clear()
        appearanceFonts.clear()
        spanFonts.clear()
    }

    private fun appearanceFont(context: Context, appearance: Int) = appearanceFonts.getOrPut(appearance) {
        val attributes = context.obtainStyledAttributes(appearance, intArrayOf(android.R.attr.fontFamily))

        try {
            attributes.getResourceId(0, 0)
        } finally {
            attributes.recycle()
        }
    }

    private fun systemFont(resources: Resources, id: Int, style: Int): Typeface {
        val name = runCatching { resources.getResourceEntryName(id) }.getOrDefault("")

        val code = "sourcecodepro" in name
        if (code) codeFonts.add(id)

        val base = if (code) Typeface.MONOSPACE else Typeface.DEFAULT
        val weight = when {
            code -> 400
            "semibold" in name -> 500
            "bold" in name -> 700
            "medium" in name -> if (name.startsWith("whitney")) 400 else 500
            else -> 400
        }

        val bold = weight >= 700 || style == Typeface.BOLD || style == Typeface.BOLD_ITALIC
        val italic = style == Typeface.ITALIC || style == Typeface.BOLD_ITALIC

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Typeface.create(base, if (bold) 700 else weight, italic)
        }

        // API < 28 has no weight
        val family = if (base === Typeface.DEFAULT && weight == 500 && !bold) {
            Typeface.create("sans-serif-medium", Typeface.NORMAL)
        } else {
            base
        }

        val legacyStyle = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }

        return Typeface.create(family, legacyStyle)
    }
}

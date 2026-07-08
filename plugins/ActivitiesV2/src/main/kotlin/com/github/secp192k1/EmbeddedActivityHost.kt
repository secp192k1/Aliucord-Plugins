package com.github.secp192k1

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import com.aliucord.Logger
import com.aliucord.Utils
import com.discord.api.channel.Channel
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.lang.ref.WeakReference

@SuppressLint("StaticFieldLeak")
internal object EmbeddedActivityHost {
    private val logger = Logger("ActivitiesV2")

    private var dialog: BottomSheetDialog? = null
    private var participantsRow: LinearLayout? = null
    private var webView: WebView? = null
    private var session: ActivitySession? = null
    private var currentActivity = WeakReference<Activity>(null)
    var onLeave: ((ActivitySession) -> Unit)? = null

    fun preload(ctx: Context) {
        (ctx.applicationContext as? Application)?.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    currentActivity = WeakReference(activity)
                }
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            }
        )

        Utils.mainThread.post {
            try {
                WebView(ctx.applicationContext).destroy()
            } catch (e: Throwable) {
                logger.error("Failed to preload WebView", e)
            }
        }
    }

    fun hostActivity(): Activity? {
        val tracked = currentActivity.get()
        if (tracked != null && !tracked.isFinishing && !tracked.isDestroyed) return tracked
        val fallback = Utils.appActivity
        return if (!fallback.isFinishing && !fallback.isDestroyed) fallback else null
    }

    fun open(appId: String, inst: String, rawInst: String, launch: String, channel: Channel, loc: String): Boolean {
        dialog?.dismiss()

        val activity = hostActivity()
        if (activity == null) {
            logger.error("Host activity not running, aborting launch", null)
            return false
        }

        val session = ActivitySession(appId, inst, rawInst, launch, channel, loc)
        this.session = session

        val web = createWebView(activity, session)
        webView = web

        val built = ActivityUi.buildDialog(activity, web) { onClosed() }
        val d = built.dialog
        dialog = d
        participantsRow = built.participantsRow
        web.loadDataWithBaseURL(
            "https://discord.com/",
            ActivityPayload.hostPage(ActivityApi.buildUrl(session)),
            "text/html",
            "utf-8",
            null,
        )

        return try {
            d.show()
            true
        } catch (e: WindowManager.BadTokenException) {
            logger.error("Invalid activity window", e)
            onClosed()
            false
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun createWebView(activity: Activity, session: ActivitySession): WebView {
        val web = WebView(activity)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowContentAccess = true
        }
        WebView.setWebContentsDebuggingEnabled(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                    logger.verbose("WORKER[${request.method}] ${request.url}")
                    return null
                }
            })
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                logger.verbose("HTTP[${request.method}] ${request.url}")
                return null
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) = request.grant(request.resources)
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                logger.verbose("[activity] ${msg.message()}")
                return true
            }
        }
        val rpc = ActivityRpc(session) { js -> web.post { web.evaluateJavascript(js, null) } }
        web.addJavascriptInterface(object {
            @JavascriptInterface
            @Suppress("unused")
            fun send(json: String) {
                try {
                    rpc.handle(json)
                } catch (e: Throwable) {
                    logger.error("Failed to handle RPC frame", e)
                }
            }
        }, "AliucordRPC")
        return web
    }

    fun updateParticipants(instanceId: String, userIds: List<Long>) {
        if (session?.rawInstanceId != instanceId) return

        Utils.mainThread.post {
            val row = participantsRow ?: return@post
            ActivityUi.updateParticipants(row, userIds)
        }
    }

    private fun onClosed() {
        val web = webView
        val current = session
        webView = null
        dialog = null
        participantsRow = null
        session = null
        (web?.parent as? ViewGroup)?.removeView(web)
        web?.destroy()
        current?.let {
            ActivityApi.leave(it)
            onLeave?.invoke(it)
        }
    }
}

package com.github.secp192k1

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
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
import android.webkit.WebResourceError
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
    private var rpc: ActivityRpc? = null
    private var currentActivity = WeakReference<Activity>(null)
    private var hostRef = WeakReference<Activity>(null)
    private var savedOrientation: Int? = null
    var onLeave: ((ActivitySession) -> Unit)? = null
    private lateinit var activityUrl: String

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
                override fun onActivityDestroyed(activity: Activity) {
                    if (dialog != null && hostRef.get() === activity) closeCurrent()
                }
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
        closeCurrent()

        val activity = hostActivity()
        if (activity == null) {
            logger.error("Host activity not running, aborting launch", null)
            return false
        }

        val session = ActivitySession(appId, inst, rawInst, launch, channel, loc)
        this.session = session
        hostRef = WeakReference(activity)
        activityUrl = ActivityApi.buildUrl(session)
        savedOrientation = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED

        val web = createWebView(activity, session)
        webView = web

        val built = ActivityUi.buildDialog(activity, web) { closeCurrent() }
        val d = built.dialog
        dialog = d
        participantsRow = built.participantsRow
        web.loadDataWithBaseURL(
            "https://discord.com/",
            ActivityPayload.hostPage(activityUrl),
            "text/html",
            "utf-8",
            null,
        )

        return try {
            d.show()
            true
        } catch (e: WindowManager.BadTokenException) {
            logger.error("Invalid activity window", e)
            closeCurrent()
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
        WebView.setWebContentsDebuggingEnabled(Config.webContentsDebugging)
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

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.url.toString() == activityUrl) onLoadFailed(error.toString())
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.url.toString() == activityUrl) onLoadFailed("HTTP ${errorResponse.statusCode}")
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                logger.verbose("[activity] handle permission: ${request.origin}")
                handlePermissionRequest(request)
            }

            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                logger.verbose("[activity] ${msg.message()}")
                return true
            }
        }

        val rpc = ActivityRpc(session) { js -> web.post { web.evaluateJavascript(js, null) } }
        this.rpc = rpc

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

        rpc?.updateParticipants(ActivityData.participants(userIds))
        Utils.mainThread.post {
            val row = participantsRow ?: return@post
            ActivityUi.updateParticipants(row, userIds)
        }
    }

    private fun handlePermissionRequest(request: PermissionRequest) {
        val resources = request.resources

        if (!Config.promptForPermissions || resources.isEmpty()) {
            request.grant(resources)
            return
        }

        val activity = hostRef.get() ?: hostActivity()
        if (activity == null || activity.isDestroyed) {
            request.deny()
            return
        }

        val labels = resources.map {
            when (it) {
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> "microphone"
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> "camera"
                else -> it.substringAfterLast('.').lowercase()
            }
        }.distinct().joinToString(" and ")

        Utils.mainThread.post {
            AlertDialog.Builder(activity)
                .setTitle("Activity permission")
                .setMessage("This activity wants to use your $labels.")
                .setPositiveButton("Allow") { _, _ -> request.grant(resources) }
                .setNegativeButton("Deny") { _, _ -> request.deny() }
                .setOnCancelListener { request.deny() }
                .setCancelable(false)
                .show()
        }
    }

    private fun onLoadFailed(reason: String) {
        logger.error("Activity failed to load: $reason", null)

        Utils.mainThread.post {
            if (dialog == null) return@post
            Utils.showToast("Activity failed to load")
            closeCurrent()
        }
    }

    private fun closeCurrent() {
        val web = webView
        val current = session
        val d = dialog
        val host = hostRef.get()
        val orientation = savedOrientation

        webView = null
        dialog = null
        participantsRow = null
        session = null
        rpc = null
        hostRef = WeakReference(null)
        savedOrientation = null
        activityUrl = ""

        if (orientation != null && host != null && !host.isDestroyed) {
            try {
                host.requestedOrientation = orientation
            } catch (e: Throwable) {
                logger.error("Failed to restore orientation", e)
            }
        }

        d?.setOnDismissListener(null)
        try {
            d?.dismiss()
        } catch (e: Throwable) {
            logger.error("Failed to dismiss activity dialog", e)
        }

        (web?.parent as? ViewGroup)?.removeView(web)
        web?.destroy()

        current?.let {
            ActivityApi.leave(it)
            onLeave?.invoke(it)
        }
    }
}

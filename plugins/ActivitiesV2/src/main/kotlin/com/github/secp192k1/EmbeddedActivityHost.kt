package com.github.secp192k1

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import com.aliucord.Logger
import com.aliucord.Utils
import com.aliucord.wrappers.ChannelWrapper.Companion.id
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
                    logger.info("Activity resumed: ${activity.javaClass.name}")
                    currentActivity = WeakReference(activity)
                }
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {
                    if (dialog != null && hostRef.get() === activity) {
                        logger.warn("Host ${activity.javaClass.name} destroyed while an activity is open, closing it")
                        closeCurrent()
                    }
                }
            }
        ) ?: logger.warn("No Application context, host activity tracking disabled")

        Utils.mainThread.post {
            try {
                val web = WebView(ctx.applicationContext)
                logger.info("WebView user agent: ${web.settings.userAgentString}")
                web.destroy()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val provider = WebView.getCurrentWebViewPackage()
                    logger.info("WebView provider: ${provider?.packageName} ${provider?.versionName}")
                }
            } catch (e: Throwable) {
                logger.error("Failed to preload WebView", e)
            }
        }
    }

    fun hostActivity(): Activity? {
        val tracked = currentActivity.get()
        if (tracked != null && !tracked.isFinishing && !tracked.isDestroyed) return tracked
        val fallback = Utils.appActivity
        if (!fallback.isFinishing && !fallback.isDestroyed) {
            logger.info("Tracked ${tracked?.javaClass?.name} unusable, falling back to ${fallback.javaClass.name}")
            return fallback
        }
        logger.warn("No usable host activity: tracked=${tracked?.javaClass?.name} fallbackFinishing=${fallback.isFinishing} fallbackDestroyed=${fallback.isDestroyed}")
        return null
    }

    fun open(appId: String, inst: String, rawInst: String, launch: String, channel: Channel, loc: String): Boolean {
        logger.info("Opening activity $appId: instance=$rawInst composite=$inst launch=$launch channel=${channel.id} location=$loc")
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
        logger.info("Locked orientation of ${activity.javaClass.name}, saved=$savedOrientation")

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
        logger.info("Creating WebView: webContentsDebugging=${Config.webContentsDebugging} promptForPermissions=${Config.promptForPermissions}")
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

            override fun onPageFinished(view: WebView, url: String?) {
                logger.info("Host page loaded: $url")
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                logger.warn("Failed ${request.method} ${request.url}: ${error.errorCode} ${error.description}")
                if (request.url.toString() == activityUrl) onLoadFailed(error.toString())
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                logger.warn("HTTP ${errorResponse.statusCode} for ${request.method} ${request.url}")
                if (request.url.toString() == activityUrl) onLoadFailed("HTTP ${errorResponse.statusCode}")
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                logger.warn("SSL error ${error.primaryError} for ${error.url}, cancelling")
                super.onReceivedSslError(view, handler, error)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                logger.warn("Renderer gone: didCrash=${detail.didCrash()} priority=${detail.rendererPriorityAtExit()}")
                return super.onRenderProcessGone(view, detail)
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                logger.verbose("[activity] handle permission: ${request.origin}")
                handlePermissionRequest(request)
            }

            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                logger.verbose("[activity] ${msg.message()}")
                if (msg.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    logger.warn("[activity] ${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})")
                }
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

        logger.info("Participants of instance $instanceId: $userIds")
        rpc?.updateParticipants(ActivityData.participants(userIds))
        Utils.mainThread.post {
            val row = participantsRow ?: return@post
            ActivityUi.updateParticipants(row, userIds)
        }
    }

    fun openExternalLink(url: String, onResult: (Boolean) -> Unit) {
        val activity = hostActivity()

        if (activity == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
            logger.warn("Refusing external link '$url', hasHostActivity=${activity != null}")
            onResult(false)
            return
        }

        logger.info("Asking to open external link $url")
        Utils.mainThread.post {
            AlertDialog.Builder(activity)
                .setTitle("Leaving Discord")
                .setMessage("This activity wants to open:\n$url")
                .setPositiveButton("Open") { _, _ ->
                    try {
                        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        logger.info("Opened external link $url")
                        onResult(true)
                    } catch (e: Throwable) {
                        logger.error("Failed to open external link", e)
                        onResult(false)
                    }
                }
                .setNegativeButton("Cancel") { _, _ ->
                    logger.info("External link cancelled")
                    onResult(false)
                }
                .setOnCancelListener { onResult(false) }
                .setCancelable(false)
                .show()
        }
    }

    private fun handlePermissionRequest(request: PermissionRequest) {
        val resources = request.resources
        logger.info("Permission request from ${request.origin}: ${resources.joinToString()}")
        warnMissingAppPermissions(resources)

        if (!Config.promptForPermissions || resources.isEmpty()) {
            logger.info("Granting without prompt: ${resources.joinToString()}")
            request.grant(resources)
            return
        }

        val activity = hostRef.get() ?: hostActivity()
        if (activity == null || activity.isDestroyed) {
            logger.warn("No host activity to prompt with, denying ${resources.joinToString()}")
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
                .setPositiveButton("Allow") { _, _ ->
                    logger.info("User allowed $labels")
                    request.grant(resources)
                }
                .setNegativeButton("Deny") { _, _ ->
                    logger.info("User denied $labels")
                    request.deny()
                }
                .setOnCancelListener { request.deny() }
                .setCancelable(false)
                .show()
        }
    }

    // A WebView grant is useless if Discord itself lacks the runtime permission
    private fun warnMissingAppPermissions(resources: Array<String>) {
        for (resource in resources) {
            val permission = when (resource) {
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
                else -> continue
            }
            val state = Utils.appContext.checkPermission(permission, Process.myPid(), Process.myUid())
            if (state != PackageManager.PERMISSION_GRANTED) {
                logger.warn("Discord lacks $permission, $resource will fail even if granted")
            }
        }
    }

    private fun onLoadFailed(reason: String) {
        logger.error("Activity failed to load: $reason", null)

        Utils.mainThread.post {
            if (dialog == null) return@post logger.info("Activity already closed, ignoring load failure")
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
        current?.let { logger.info("Closing activity ${it.applicationId}, instance=${it.rawInstanceId}") }

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
                logger.info("Restored orientation $orientation")
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

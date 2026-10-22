package com.bbw.browser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Environment
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/** Callbacks from the tab WebViews to whichever Activity is currently visible. */
interface UiListener {
    fun onTabUpdated(tab: Tab)
    fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback)
    fun onHideCustomView()
    fun onFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams
    ): Boolean
}

/**
 * Owns every tab's WebView. WebViews are created with an application-level context
 * (wrapped in a MutableContextWrapper) so they can move between the Activity and the
 * overlay window without being recreated. That is what keeps the session alive.
 */
object TabManager {
    private const val DESKTOP_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"

    private lateinit var app: Context
    private var nextId = 1

    /**
     * Default WebView user agent without the "; wv" and "Version/4.0" markers, so sites
     * (Google sign-in in particular) treat it like a normal Chrome browser.
     */
    private val mobileUa: String by lazy {
        WebSettings.getDefaultUserAgent(app)
            .replace("; wv)", ")")
            .replace(" Version/4.0", "")
    }

    val tabs = mutableListOf<Tab>()

    var currentIndex = 0
        private set

    var ui: UiListener? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun current(): Tab = tabs[currentIndex]

    fun newTab(url: String?, select: Boolean = true, lazy: Boolean = false): Tab {
        val ctx = MutableContextWrapper(app)
        val webView = WebView(ctx)
        val tab = Tab(nextId++, webView, ctx)
        configure(tab)

        val previous = if (select) tabs.getOrNull(currentIndex) else null
        tabs.add(tab)

        val target = url ?: Prefs.engine().home
        tab.url = target
        tab.title = target
        if (lazy) {
            tab.pendingUrl = target
        } else {
            webView.loadUrl(target)
        }

        if (select) {
            if (OverlayManager.mode == OverlayManager.Mode.NONE) previous?.webView?.onPause()
            currentIndex = tabs.lastIndex
        }
        return tab
    }

    fun select(index: Int) {
        if (index !in tabs.indices) return
        if (index != currentIndex && OverlayManager.mode == OverlayManager.Mode.NONE) {
            tabs.getOrNull(currentIndex)?.webView?.onPause()
        }
        currentIndex = index
        activate(tabs[index])
        saveState()
    }

    fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val tab = tabs.removeAt(index)
        (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
        tab.webView.stopLoading()
        tab.webView.destroy()
        if (tabs.isEmpty()) {
            newTab(null)
            saveState()
            return
        }
        if (index < currentIndex) currentIndex--
        if (currentIndex > tabs.lastIndex) currentIndex = tabs.lastIndex
        activate(tabs[currentIndex])
        saveState()
    }

    private fun activate(tab: Tab) {
        tab.webView.onResume()
        tab.pendingUrl?.let {
            tab.pendingUrl = null
            tab.webView.loadUrl(it)
        }
    }

    // ---- Activity / lifecycle helpers ----

    fun attachActivity(activityContext: Context) {
        tabs.forEach { it.ctx.setBaseContext(activityContext) }
    }

    fun detachActivity() {
        tabs.forEach { it.ctx.setBaseContext(app) }
    }

    fun resumeCurrent() {
        if (tabs.isEmpty()) return
        val webView = current().webView
        webView.resumeTimers()
        webView.onResume()
    }

    fun pauseAll() {
        if (tabs.isEmpty()) return
        tabs.forEach { it.webView.onPause() }
        tabs[0].webView.pauseTimers()
    }

    fun pauseAllExceptCurrent() {
        tabs.forEachIndexed { index, tab ->
            if (index != currentIndex) tab.webView.onPause()
        }
    }

    // ---- Tab actions ----

    fun setDesktop(tab: Tab, enabled: Boolean) {
        tab.desktop = enabled
        tab.webView.settings.userAgentString = if (enabled) DESKTOP_UA else mobileUa
        tab.webView.reload()
    }

    fun clearData() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        tabs.forEach {
            it.webView.clearCache(true)
            it.webView.clearHistory()
        }
    }

    // ---- Persistence of the tab list (URLs only) ----

    fun saveState() {
        if (tabs.isEmpty()) return
        val home = Prefs.engine().home
        Prefs.savedTabs = tabs.joinToString("\n") { tab ->
            val u = tab.url
            if (u.startsWith("http://") || u.startsWith("https://")) u else home
        }
        Prefs.savedIndex = currentIndex
    }

    fun restoreState() {
        val saved = Prefs.savedTabs.split("\n").filter {
            it.startsWith("http://") || it.startsWith("https://")
        }
        if (saved.isEmpty()) {
            newTab(null)
            return
        }
        val index = Prefs.savedIndex.coerceIn(0, saved.lastIndex)
        saved.forEachIndexed { i, url -> newTab(url, select = false, lazy = i != index) }
        currentIndex = index
    }

    // ---- WebView setup ----

    private fun dispatchUpdate(tab: Tab) {
        ui?.onTabUpdated(tab)
        OverlayManager.onTabUpdated(tab)
    }

    private fun toast(message: String) {
        Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(tab: Tab) {
        val webView = tab.webView
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            if (Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = true
            userAgentString = mobileUa
        }
        // Stop WebView sending the "X-Requested-With: <package name>" header that Google uses to spot embedded browsers
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(webView.settings, emptySet())
        }
        CookieManager.getInstance().setAcceptCookie(true)
        // Google sign-in needs cookies shared between its own domains
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                if (request == null) return true
                val uri = request.url ?: return true
                val scheme = uri.scheme?.lowercase()
                if (scheme == "http" || scheme == "https") return false
                // Only user-initiated mailto/tel links are handed to other apps; everything else is blocked
                if (request.hasGesture() && (scheme == "mailto" || scheme == "tel")) {
                    try {
                        app.startActivity(
                            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Exception) {
                    }
                }
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (url != null) tab.url = url
                dispatchUpdate(tab)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (url != null) tab.url = url
                tab.progress = 100
                dispatchUpdate(tab)
                saveState()
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                if (url != null) tab.url = url
                dispatchUpdate(tab)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (view == null || request == null || !request.isForMainFrame) return
                showError(view, request.url.toString(), error?.description?.toString() ?: "Unknown error")
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                handler?.cancel()
                if (view != null) {
                    showError(view, error?.url ?: tab.url, "The connection is not secure")
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                tab.progress = newProgress
                dispatchUpdate(tab)
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) {
                    tab.title = title
                    dispatchUpdate(tab)
                }
            }

            override fun onShowCustomView(
                view: View?,
                callback: WebChromeClient.CustomViewCallback?
            ) {
                if (view == null || callback == null) return
                val listener = ui
                if (listener == null) {
                    callback.onCustomViewHidden()
                } else {
                    listener.onShowCustomView(view, callback)
                }
            }

            override fun onHideCustomView() {
                ui?.onHideCustomView()
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: WebChromeClient.FileChooserParams?
            ): Boolean {
                if (filePathCallback == null || fileChooserParams == null) return false
                val listener = ui
                if (listener == null) {
                    filePathCallback.onReceiveValue(null)
                    return true
                }
                return listener.onFileChooser(filePathCallback, fileChooserParams)
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(url, userAgent, contentDisposition, mimeType)
        }
    }

    private fun showError(view: WebView, failingUrl: String, message: String) {
        val html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>" +
            "<body style='font-family:sans-serif;padding:24px;color:#444'>" +
            "<h2>Can't open this page</h2>" +
            "<p>${TextUtils.htmlEncode(message)}</p>" +
            "<p style='word-break:break-all;color:#888'>${TextUtils.htmlEncode(failingUrl)}</p>" +
            "</body></html>"
        view.loadDataWithBaseURL(failingUrl, html, "text/html", "UTF-8", failingUrl)
    }

    private fun startDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        val scheme = Uri.parse(url).scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            toast("This download type is not supported")
            return
        }
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
                if (!userAgent.isNullOrBlank()) addRequestHeader("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                if (Build.VERSION.SDK_INT >= 29) {
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                } else {
                    setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, fileName)
                }
            }
            val manager = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            toast("Downloading $fileName")
        } catch (e: Exception) {
            toast("Download failed")
        }
    }
}

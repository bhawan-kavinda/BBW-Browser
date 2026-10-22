package com.bbw.browser

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PendingRequest(
    val url: String,
    val method: String,
    val host: String,
    val isMainFrame: Boolean,
    val headers: Map<String, String>,
    val cookies: String,
    val tabTitle: String
) {
    val latch = CountDownLatch(1)

    @Volatile
    var allowed = false

    fun describe(): String = buildString {
        append(if (isMainFrame) "Type: page load\n" else "Type: sub-resource (image, script, XHR...)\n")
        append("Method: $method\n")
        append("Host: $host\n")
        append("From tab: $tabTitle\n\n")
        append("Headers:\n")
        if (headers.isEmpty()) append("  (none)\n") else headers.forEach { (k, v) -> append("  $k: $v\n") }
        append("\nCookies that will be sent:\n")
        if (cookies.isBlank()) append("  (none)\n") else cookies.split("; ").forEach { append("  $it\n") }
    }
}

/**
 * Holds every request of the browser until the user allows or blocks it.
 *
 * WebViewClient.shouldInterceptRequest runs on a background thread, so it can simply wait for
 * the answer. Returning null lets the WebView send the request itself (with its cookies).
 * Only works while the browser UI is visible; with no UI there is nobody to ask, so requests pass.
 */
object RequestInterceptor {
    private const val TIMEOUT_SECONDS = 60L

    private val main = Handler(Looper.getMainLooper())
    private val allowedHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Main thread only. */
    val queue = ArrayList<PendingRequest>()

    /** Main thread only. Called whenever the queue changes. */
    var listener: (() -> Unit)? = null

    /** Background thread. Returns null to continue the request, or a response that blocks it. */
    fun intercept(tab: Tab, request: WebResourceRequest): WebResourceResponse? {
        if (!Prefs.intercept) return null
        if (TabManager.ui == null) return null
        val scheme = request.url.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        if (Prefs.interceptMainOnly && !request.isForMainFrame) return null
        val host = request.url.host ?: ""
        if (host in allowedHosts) return null

        val url = request.url.toString()
        val pending = PendingRequest(
            url = url,
            method = request.method ?: "GET",
            host = host,
            isMainFrame = request.isForMainFrame,
            headers = request.requestHeaders ?: emptyMap(),
            cookies = CookieManager.getInstance().getCookie(url) ?: "",
            tabTitle = tab.title.ifBlank { tab.url }
        )

        main.post {
            if (TabManager.ui == null) {
                pending.allowed = true
                pending.latch.countDown()
            } else {
                queue.add(pending)
                listener?.invoke()
            }
        }

        val answered = try {
            pending.latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            false
        }
        if (!answered) {
            main.post {
                queue.remove(pending)
                listener?.invoke()
            }
        }
        return if (answered && pending.allowed) null else blocked()
    }

    private fun blocked() = WebResourceResponse(
        "text/plain",
        "UTF-8",
        403,
        "Blocked",
        emptyMap(),
        ByteArrayInputStream(ByteArray(0))
    )

    // ---- decisions (main thread) ----

    private fun resolve(p: PendingRequest, ok: Boolean) {
        p.allowed = ok
        p.latch.countDown()
        queue.remove(p)
    }

    fun allow(p: PendingRequest) {
        resolve(p, true)
        listener?.invoke()
    }

    fun block(p: PendingRequest) {
        resolve(p, false)
        listener?.invoke()
    }

    fun allowHost(p: PendingRequest) {
        allowedHosts.add(p.host)
        queue.filter { it.host == p.host }.forEach { resolve(it, true) }
        listener?.invoke()
    }

    fun allowAllQueued() {
        queue.toList().forEach { resolve(it, true) }
        listener?.invoke()
    }

    /** Called when interception is switched off: forget remembered sites and let everything waiting pass. */
    fun reset() {
        allowedHosts.clear()
        allowAllQueued()
    }
}

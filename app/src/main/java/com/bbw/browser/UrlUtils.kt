package com.bbw.browser

import android.net.Uri

object UrlUtils {
    private val LOCAL_OR_IP =
        Regex("^(localhost|(\\d{1,3}\\.){3}\\d{1,3})(:\\d+)?(/.*)?$", RegexOption.IGNORE_CASE)

    /** Turns whatever the user typed into a loadable http(s) URL or a search URL. */
    fun resolve(input: String): String {
        val text = input.trim()
        val engine = Prefs.engine()
        if (text.isEmpty()) return engine.home
        val lower = text.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return text
        if (LOCAL_OR_IP.matches(text)) return "http://$text"
        val looksLikeHost = !text.contains(' ') && text.contains('.') && !text.endsWith(".")
        return if (looksLikeHost) {
            "https://$text"
        } else {
            engine.searchUrl.replace("%s", Uri.encode(text))
        }
    }
}

package com.bbw.browser

import android.content.MutableContextWrapper
import android.webkit.WebView

class Tab(
    val id: Int,
    val webView: WebView,
    val ctx: MutableContextWrapper
) {
    var title: String = ""
    var url: String = ""
    var progress: Int = 100
    var desktop: Boolean = false

    /** URL waiting to be loaded the first time this tab is selected (restored tabs). */
    var pendingUrl: String? = null
}

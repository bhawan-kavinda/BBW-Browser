package com.bbw.browser

import android.content.Context
import android.content.SharedPreferences

data class Engine(val name: String, val home: String, val searchUrl: String)

object Prefs {
    val ENGINES = listOf(
        Engine("Google", "https://www.google.com", "https://www.google.com/search?q=%s"),
        Engine("DuckDuckGo", "https://duckduckgo.com", "https://duckduckgo.com/?q=%s"),
        Engine("Bing", "https://www.bing.com", "https://www.bing.com/search?q=%s")
    )

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences("bbw_prefs", Context.MODE_PRIVATE)
    }

    var keepAlive: Boolean
        get() = sp.getBoolean("keep_alive", true)
        set(value) = sp.edit().putBoolean("keep_alive", value).apply()

    var engineIndex: Int
        get() = sp.getInt("engine", 0).coerceIn(0, ENGINES.lastIndex)
        set(value) = sp.edit().putInt("engine", value).apply()

    var askedOverlay: Boolean
        get() = sp.getBoolean("asked_overlay", false)
        set(value) = sp.edit().putBoolean("asked_overlay", value).apply()

    var savedTabs: String
        get() = sp.getString("saved_tabs", "") ?: ""
        set(value) = sp.edit().putString("saved_tabs", value).apply()

    var savedIndex: Int
        get() = sp.getInt("saved_index", 0)
        set(value) = sp.edit().putInt("saved_index", value).apply()

    var intercept: Boolean
        get() = sp.getBoolean("intercept", false)
        set(value) = sp.edit().putBoolean("intercept", value).apply()

    var interceptMainOnly: Boolean
        get() = sp.getBoolean("intercept_main_only", false)
        set(value) = sp.edit().putBoolean("intercept_main_only", value).apply()

    var zoomButtons: Boolean
        get() = sp.getBoolean("zoom_buttons", true)
        set(value) = sp.edit().putBoolean("zoom_buttons", value).apply()

    var textZoom: Int
        get() = sp.getInt("text_zoom", 100)
        set(value) = sp.edit().putInt("text_zoom", value).apply()

    fun engine(): Engine = ENGINES[engineIndex]
}

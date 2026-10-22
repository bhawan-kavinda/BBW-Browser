package com.bbw.browser

import android.app.Application

class BbwApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        TabManager.init(this)
        OverlayManager.init(this)
    }
}

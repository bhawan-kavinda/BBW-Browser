package com.bbw.browser

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Hosts the active tab's WebView inside a "display over other apps" window.
 *
 *  - BACKGROUND: a full-size, fully transparent, touch-through window. The page keeps
 *    running (timers, sockets, media) while the user works in other apps.
 *  - FLOATING: a small draggable window with a title bar so the page can be used on top
 *    of other apps.
 *
 * Everything here runs on the main thread.
 */
object OverlayManager {
    enum class Mode { NONE, BACKGROUND, FLOATING }

    var mode = Mode.NONE
        private set

    private lateinit var app: Context
    private lateinit var wm: WindowManager

    private var root: View? = null
    private var hosted: Tab? = null
    private var params: WindowManager.LayoutParams? = null
    private var titleView: TextView? = null

    fun init(context: Context) {
        app = context.applicationContext
        wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    fun canDraw(): Boolean = Settings.canDrawOverlays(app)

    @Suppress("DEPRECATION")
    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

    /** Moves the current tab into an invisible overlay window so it keeps running. */
    fun showBackground(): Boolean {
        if (!canDraw()) return false
        release()
        val tab = TabManager.current()
        val webView = tab.webView
        (webView.parent as? ViewGroup)?.removeView(webView)

        val dm = app.resources.displayMetrics
        val p = WindowManager.LayoutParams(
            dm.widthPixels,
            dm.heightPixels,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 0f
        }

        return try {
            wm.addView(webView, p)
            webView.resumeTimers()
            webView.onResume()
            root = webView
            hosted = tab
            params = p
            mode = Mode.BACKGROUND
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Moves the current tab into a small draggable floating window. */
    fun showFloating(): Boolean {
        if (!canDraw()) return false
        release()
        val tab = TabManager.current()
        val webView = tab.webView
        (webView.parent as? ViewGroup)?.removeView(webView)

        val dm = app.resources.displayMetrics
        val container = buildFloat(tab)
        val p = WindowManager.LayoutParams(
            (dm.widthPixels * 0.92f).toInt(),
            (dm.heightPixels * 0.5f).toInt(),
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (dm.widthPixels * 0.04f).toInt()
            y = (dm.heightPixels * 0.12f).toInt()
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }

        return try {
            wm.addView(container, p)
            webView.resumeTimers()
            webView.onResume()
            root = container
            hosted = tab
            params = p
            mode = Mode.FLOATING
            true
        } catch (e: Exception) {
            container.removeView(webView)
            webView.setOnTouchListener(null)
            titleView = null
            false
        }
    }

    /** Removes whatever overlay is showing and frees the WebView so an Activity can take it back. */
    fun release() {
        val r = root
        if (r == null) {
            mode = Mode.NONE
            return
        }
        val webView = hosted?.webView
        try {
            webView?.setOnTouchListener(null)
            if (webView != null && r !== webView) {
                (webView.parent as? ViewGroup)?.removeView(webView)
            }
            wm.removeViewImmediate(r)
        } catch (_: Exception) {
        }
        root = null
        hosted = null
        params = null
        titleView = null
        mode = Mode.NONE
    }

    fun onTabUpdated(tab: Tab) {
        if (tab === hosted) {
            titleView?.text = tab.title.ifBlank { tab.url }
        }
    }

    // The floating window is not focusable by default so the app underneath keeps its keyboard.
    // It becomes focusable only while the user is touching it.
    private fun setFloatFocusable(focusable: Boolean) {
        val r = root ?: return
        val p = params ?: return
        val newFlags = if (focusable) {
            p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (newFlags != p.flags) {
            p.flags = newFlags
            try {
                wm.updateViewLayout(r, p)
            } catch (_: Exception) {
            }
        }
    }

    private fun openApp() {
        val intent = Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try {
            app.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    private fun iconButton(
        resId: Int,
        description: String,
        size: Int,
        onClick: () -> Unit
    ): ImageButton = ImageButton(app).apply {
        setImageResource(resId)
        setColorFilter(Color.WHITE)
        setBackgroundColor(Color.TRANSPARENT)
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = description
        setPadding(size / 4, size / 4, size / 4, size / 4)
        setOnClickListener { onClick() }
    }

    private fun buildFloat(tab: Tab): LinearLayout {
        val density = app.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val container = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xFF202124.toInt())
                cornerRadius = dp(14).toFloat()
            }
            clipToOutline = true
            elevation = dp(8).toFloat()
        }

        val bar = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(4), 0)
        }
        val title = TextView(app).apply {
            text = tab.title.ifBlank { tab.url }
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        titleView = title
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val buttonSize = dp(40)
        bar.addView(
            iconButton(R.drawable.ic_arrow_back, "Back", buttonSize) {
                if (tab.webView.canGoBack()) tab.webView.goBack()
            },
            LinearLayout.LayoutParams(buttonSize, buttonSize)
        )
        bar.addView(
            iconButton(R.drawable.ic_open_full, "Open app", buttonSize) { openApp() },
            LinearLayout.LayoutParams(buttonSize, buttonSize)
        )
        bar.addView(
            iconButton(R.drawable.ic_minimize, "Hide", buttonSize) {
                container.post { showBackground() }
            },
            LinearLayout.LayoutParams(buttonSize, buttonSize)
        )

        // Drag the window by its title bar
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        bar.setOnTouchListener { _, event ->
            val p = params
            if (p != null) {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = p.x
                        startY = p.y
                        touchX = event.rawX
                        touchY = event.rawY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        p.x = startX + (event.rawX - touchX).toInt()
                        p.y = startY + (event.rawY - touchY).toInt()
                        root?.let {
                            try {
                                wm.updateViewLayout(it, p)
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            }
            true
        }

        container.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) setFloatFocusable(false)
            false
        }
        tab.webView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) setFloatFocusable(true)
            false
        }

        container.addView(
            bar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40))
        )
        container.addView(
            tab.webView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        return container
    }
}

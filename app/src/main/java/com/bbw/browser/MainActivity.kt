package com.bbw.browser

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle

class MainActivity : AppCompatActivity(), UiListener {

    private lateinit var address: EditText
    private lateinit var btnReload: ImageButton
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var progress: ProgressBar
    private lateinit var webContainer: FrameLayout
    private lateinit var fullscreen: FrameLayout
    private lateinit var tabCount: TextView
    private lateinit var zoomPanel: View

    // Request approval sheet
    private var interceptDialog: AlertDialog? = null
    private var shownRequest: PendingRequest? = null
    private lateinit var icCount: TextView
    private lateinit var icSummary: TextView
    private lateinit var icDetails: TextView

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val fileLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            fileCallback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            )
            fileCallback = null
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        address = findViewById(R.id.address)
        btnReload = findViewById(R.id.btn_reload)
        btnBack = findViewById(R.id.btn_back)
        btnForward = findViewById(R.id.btn_forward)
        progress = findViewById(R.id.progress)
        webContainer = findViewById(R.id.web_container)
        fullscreen = findViewById(R.id.fullscreen)
        tabCount = findViewById(R.id.tab_count)
        zoomPanel = findViewById(R.id.zoom_panel)
        zoomPanel.visibility = if (Prefs.zoomButtons) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btn_zoom_in).setOnClickListener { zoom(true) }
        findViewById<View>(R.id.btn_zoom_out).setOnClickListener { zoom(false) }

        setupListeners()
        setupBackHandling()

        if (TabManager.tabs.isEmpty()) TabManager.restoreState()

        if (savedInstanceState == null) {
            handleViewIntent(intent)
            askOverlayOnFirstRun()
            if (Prefs.keepAlive) askNotificationPermission()
        }
    }

    override fun onStart() {
        super.onStart()
        // Take the WebView back from the overlay window before attaching it here
        OverlayManager.release()
        TabManager.ui = this
        TabManager.attachActivity(this)
        RequestInterceptor.listener = { refreshInterceptSheet() }
        TabManager.resumeCurrent()
        attachCurrent()

        if (Prefs.keepAlive && OverlayManager.canDraw()) {
            KeepAliveService.start(this)
        } else {
            KeepAliveService.stop(this)
        }
    }

    override fun onStop() {
        super.onStop()
        TabManager.ui = null
        // Nobody can answer while the UI is hidden: close the sheet and let waiting requests pass
        RequestInterceptor.listener = null
        interceptDialog?.dismiss()
        interceptDialog = null
        shownRequest = null
        RequestInterceptor.allowAllQueued()
        if (customView != null) onHideCustomView()
        android.webkit.CookieManager.getInstance().flush()
        TabManager.saveState()
        webContainer.removeAllViews()

        when {
            // The floating window already hosts the current tab
            OverlayManager.mode == OverlayManager.Mode.FLOATING ||
                OverlayManager.mode == OverlayManager.Mode.BUBBLE ->
                TabManager.pauseAllExceptCurrent()
            // Hand the current tab to the invisible overlay so it keeps running
            Prefs.keepAlive && OverlayManager.showBackground() ->
                TabManager.pauseAllExceptCurrent()
            else -> TabManager.pauseAll()
        }
        TabManager.detachActivity()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
    }

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return
        TabManager.newTab(uri.toString())
        // When stopped, onStart will attach the new tab
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) attachCurrent()
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val webView = TabManager.current().webView
                when {
                    customView != null -> onHideCustomView()
                    webView.canGoBack() -> webView.goBack()
                    Prefs.keepAlive && OverlayManager.canDraw() -> moveTaskToBack(true)
                    else -> finish()
                }
            }
        })
    }

    // ------------------------------------------------------------------ UI wiring

    private fun setupListeners() {
        address.setOnEditorActionListener { view, actionId, event ->
            val enterPressed = event != null &&
                event.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || enterPressed) {
                loadFromAddress(view.text.toString())
                true
            } else {
                false
            }
        }
        address.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) render(TabManager.current())
        }

        btnReload.setOnClickListener {
            val tab = TabManager.current()
            if (tab.progress in 0..99) {
                tab.webView.stopLoading()
                tab.progress = 100
                render(tab)
            } else {
                tab.webView.reload()
            }
        }
        btnBack.setOnClickListener {
            val webView = TabManager.current().webView
            if (webView.canGoBack()) webView.goBack()
        }
        btnForward.setOnClickListener {
            val webView = TabManager.current().webView
            if (webView.canGoForward()) webView.goForward()
        }
        findViewById<ImageButton>(R.id.btn_home).setOnClickListener {
            TabManager.current().webView.loadUrl(Prefs.engine().home)
        }
        findViewById<View>(R.id.btn_tabs).setOnClickListener { showTabs() }
        findViewById<ImageButton>(R.id.btn_menu).setOnClickListener { showMenu(it) }
    }

    private fun loadFromAddress(text: String) {
        val tab = TabManager.current()
        tab.pendingUrl = null
        tab.webView.loadUrl(UrlUtils.resolve(text))
        address.clearFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(address.windowToken, 0)
    }

    private fun attachCurrent() {
        val tab = TabManager.current()
        val webView = tab.webView
        if (webView.parent !== webContainer) {
            webContainer.removeAllViews()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webContainer.addView(
                webView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        render(tab)
    }

    private fun render(tab: Tab) {
        if (!address.hasFocus()) address.setText(tab.url)
        val loading = tab.progress in 0..99
        progress.visibility = if (loading) View.VISIBLE else View.GONE
        progress.progress = tab.progress
        btnReload.setImageResource(if (loading) R.drawable.ic_close else R.drawable.ic_refresh)
        btnBack.alpha = if (tab.webView.canGoBack()) 1f else 0.4f
        btnForward.alpha = if (tab.webView.canGoForward()) 1f else 0.4f
        tabCount.text = TabManager.tabs.size.toString()
    }

    // ------------------------------------------------------------------ UiListener

    override fun onTabUpdated(tab: Tab) {
        if (tab === TabManager.current()) render(tab)
    }

    override fun onNewWindowRequest(fromUrl: String, resultMsg: Message) {
        val host = Uri.parse(fromUrl).host ?: fromUrl
        AlertDialog.Builder(this)
            .setTitle("Open in a new tab?")
            .setMessage("$host wants to open a new tab.")
            .setCancelable(false)
            .setPositiveButton("Open") { _, _ ->
                TabManager.acceptNewWindow(resultMsg)
                attachCurrent()
            }
            .setNegativeButton("Don't allow") { _, _ -> TabManager.denyNewWindow(resultMsg) }
            .show()
    }

    override fun onTabsChanged() {
        attachCurrent()
    }

    override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customCallback = callback
        fullscreen.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        fullscreen.visibility = View.VISIBLE
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onHideCustomView() {
        val view = customView ?: return
        val callback = customCallback
        customView = null
        customCallback = null
        fullscreen.removeView(view)
        fullscreen.visibility = View.GONE
        WindowCompat.getInsetsController(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
        callback?.onCustomViewHidden()
    }

    override fun onFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams
    ): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = callback
        try {
            fileLauncher.launch(params.createIntent())
        } catch (e: Exception) {
            fileCallback = null
            callback.onReceiveValue(null)
        }
        return true
    }

    // ------------------------------------------------------------------ tabs dialog

    private fun showTabs() {
        val content = layoutInflater.inflate(R.layout.dialog_tabs, null)
        val list = content.findViewById<ListView>(R.id.tab_list)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Tabs")
            .setView(content)
            .setPositiveButton("New tab") { _, _ ->
                TabManager.newTab(null)
                attachCurrent()
            }
            .setNegativeButton("Close", null)
            .create()

        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = TabManager.tabs.size

            override fun getItem(position: Int): Any = TabManager.tabs[position]

            override fun getItemId(position: Int): Long = TabManager.tabs[position].id.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val row = convertView ?: layoutInflater.inflate(R.layout.item_tab, parent, false)
                val tab = TabManager.tabs[position]
                val title = row.findViewById<TextView>(R.id.tab_title)
                title.text = tab.title.ifBlank { tab.url }
                title.setTypeface(
                    null,
                    if (position == TabManager.currentIndex) Typeface.BOLD else Typeface.NORMAL
                )
                row.setOnClickListener {
                    TabManager.select(position)
                    attachCurrent()
                    dialog.dismiss()
                }
                row.findViewById<ImageButton>(R.id.tab_close).setOnClickListener {
                    TabManager.closeTab(position)
                    attachCurrent()
                    notifyDataSetChanged()
                }
                return row
            }
        }
        list.adapter = adapter
        dialog.show()
    }

    // ------------------------------------------------------------------ menu

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.browser_menu, popup.menu)
        popup.menu.findItem(R.id.m_keep_alive).isChecked =
            Prefs.keepAlive && OverlayManager.canDraw()
        popup.menu.findItem(R.id.m_desktop).isChecked = TabManager.current().desktop
        popup.setOnMenuItemClickListener { item ->
            onMenuItem(item.itemId)
            true
        }
        popup.show()
    }

    private fun onMenuItem(id: Int) {
        val tab = TabManager.current()
        when (id) {
            R.id.m_new_tab -> {
                TabManager.newTab(null)
                attachCurrent()
            }
            R.id.m_floating -> startOverlay(bubble = false)
            R.id.m_bubble -> startOverlay(bubble = true)
            R.id.m_settings -> showSettings()
            R.id.m_keep_alive -> toggleKeepAlive()
            R.id.m_desktop -> TabManager.setDesktop(tab, !tab.desktop)
            R.id.m_share -> shareUrl(tab.url)
            R.id.m_engine -> chooseEngine()
            R.id.m_clear -> confirmClear()
        }
    }

    private fun startOverlay(bubble: Boolean) {
        if (!OverlayManager.canDraw()) {
            requestOverlayPermission()
            return
        }
        KeepAliveService.start(this)
        webContainer.removeAllViews()
        val shown = if (bubble) OverlayManager.showBubble() else OverlayManager.showFloating()
        if (shown) {
            moveTaskToBack(true)
        } else {
            attachCurrent()
            toast("Could not open the floating window")
        }
    }

    // ------------------------------------------------------------------ zoom

    private fun zoom(zoomIn: Boolean) {
        val webView = TabManager.current().webView
        val changed = if (zoomIn) webView.zoomIn() else webView.zoomOut()
        if (!changed) {
            // Page forbids pinch zoom (or is at its limit): scale the text instead
            val settings = webView.settings
            settings.textZoom = (settings.textZoom + if (zoomIn) 10 else -10).coerceIn(50, 300)
        }
    }

    // ------------------------------------------------------------------ settings

    private fun showSettings() {
        val v = layoutInflater.inflate(R.layout.dialog_settings, null)
        val keep = v.findViewById<CheckBox>(R.id.s_keep_alive)
        val intercept = v.findViewById<CheckBox>(R.id.s_intercept)
        val mainOnly = v.findViewById<CheckBox>(R.id.s_intercept_main)
        val zoomButtons = v.findViewById<CheckBox>(R.id.s_zoom_buttons)
        val textSize = v.findViewById<TextView>(R.id.s_text_size)
        val engine = v.findViewById<TextView>(R.id.s_engine)
        val clear = v.findViewById<TextView>(R.id.s_clear)

        keep.isChecked = Prefs.keepAlive && OverlayManager.canDraw()
        intercept.isChecked = Prefs.intercept
        mainOnly.isChecked = Prefs.interceptMainOnly
        mainOnly.isEnabled = Prefs.intercept
        zoomButtons.isChecked = Prefs.zoomButtons
        textSize.text = "Text size: ${Prefs.textZoom}%"
        engine.text = "Search engine: ${Prefs.engine().name}"

        keep.setOnClickListener {
            if (keep.isChecked) {
                if (!OverlayManager.canDraw()) {
                    keep.isChecked = false
                    requestOverlayPermission()
                } else {
                    Prefs.keepAlive = true
                    KeepAliveService.start(this)
                    askNotificationPermission()
                }
            } else {
                Prefs.keepAlive = false
                KeepAliveService.stop(this)
            }
        }
        intercept.setOnClickListener {
            Prefs.intercept = intercept.isChecked
            mainOnly.isEnabled = intercept.isChecked
            if (intercept.isChecked) {
                toast("Requests now wait for your approval")
            } else {
                RequestInterceptor.reset()
            }
        }
        mainOnly.setOnClickListener { Prefs.interceptMainOnly = mainOnly.isChecked }
        zoomButtons.setOnClickListener {
            Prefs.zoomButtons = zoomButtons.isChecked
            zoomPanel.visibility = if (zoomButtons.isChecked) View.VISIBLE else View.GONE
        }
        textSize.setOnClickListener {
            chooseTextSize { textSize.text = "Text size: ${Prefs.textZoom}%" }
        }
        engine.setOnClickListener {
            chooseEngine { engine.text = "Search engine: ${Prefs.engine().name}" }
        }
        clear.setOnClickListener { confirmClear() }

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(v)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun chooseTextSize(onDone: () -> Unit) {
        val values = intArrayOf(75, 100, 125, 150, 200)
        val labels = values.map { "$it%" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Text size")
            .setSingleChoiceItems(labels, values.indexOf(Prefs.textZoom).coerceAtLeast(1)) { dialog, which ->
                TabManager.applyTextZoom(values[which])
                dialog.dismiss()
                onDone()
            }
            .show()
    }

    // ------------------------------------------------------------------ request approval sheet

    private fun refreshInterceptSheet() {
        val first = RequestInterceptor.queue.firstOrNull()
        if (first == null) {
            interceptDialog?.dismiss()
            interceptDialog = null
            shownRequest = null
            return
        }
        if (interceptDialog == null) buildInterceptSheet()
        shownRequest = first
        icCount.text = "${RequestInterceptor.queue.size} waiting"
        icSummary.text = "${first.method}  ${first.url}"
        icDetails.text = first.describe()
    }

    private fun buildInterceptSheet() {
        val view = layoutInflater.inflate(R.layout.dialog_intercept, null)
        icCount = view.findViewById(R.id.ic_count)
        icSummary = view.findViewById(R.id.ic_summary)
        icDetails = view.findViewById(R.id.ic_details)
        view.findViewById<View>(R.id.ic_allow).setOnClickListener {
            shownRequest?.let { RequestInterceptor.allow(it) }
        }
        view.findViewById<View>(R.id.ic_block).setOnClickListener {
            shownRequest?.let { RequestInterceptor.block(it) }
        }
        view.findViewById<View>(R.id.ic_allow_site).setOnClickListener {
            shownRequest?.let { RequestInterceptor.allowHost(it) }
        }
        view.findViewById<View>(R.id.ic_allow_all).setOnClickListener {
            RequestInterceptor.allowAllQueued()
        }
        view.findViewById<View>(R.id.ic_stop).setOnClickListener {
            Prefs.intercept = false
            RequestInterceptor.reset()
        }
        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(false)
            .create()
        dialog.window?.setGravity(Gravity.BOTTOM)
        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        interceptDialog = dialog
    }

    private fun toggleKeepAlive() {
        val active = Prefs.keepAlive && OverlayManager.canDraw()
        if (active) {
            Prefs.keepAlive = false
            KeepAliveService.stop(this)
            toast("Background session turned off")
            return
        }
        if (!OverlayManager.canDraw()) {
            requestOverlayPermission()
            return
        }
        Prefs.keepAlive = true
        KeepAliveService.start(this)
        askNotificationPermission()
        toast("Background session turned on")
    }

    private fun shareUrl(url: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, url)
        startActivity(Intent.createChooser(send, null))
    }

    private fun chooseEngine(onDone: () -> Unit = {}) {
        val names = Prefs.ENGINES.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Search engine")
            .setSingleChoiceItems(names, Prefs.engineIndex) { dialog, which ->
                Prefs.engineIndex = which
                dialog.dismiss()
                onDone()
            }
            .show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Clear browsing data")
            .setMessage("Cookies, site data, cache and history will be removed. You will be signed out of websites.")
            .setPositiveButton("Clear") { _, _ ->
                TabManager.clearData()
                render(TabManager.current())
                toast("Browsing data cleared")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------ permissions

    private fun requestOverlayPermission() {
        toast("Allow \"Display over other apps\" for BBW Browser")
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun askOverlayOnFirstRun() {
        if (Prefs.askedOverlay || OverlayManager.canDraw()) return
        Prefs.askedOverlay = true
        AlertDialog.Builder(this)
            .setTitle("Keep your session alive")
            .setMessage("Allow \"Display over other apps\" so BBW Browser keeps your web session running while you use other apps.")
            .setPositiveButton("Allow") { _, _ -> requestOverlayPermission() }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

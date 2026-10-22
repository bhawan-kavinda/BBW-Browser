package com.bbw.browser

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.BaseAdapter
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
        if (customView != null) onHideCustomView()
        android.webkit.CookieManager.getInstance().flush()
        TabManager.saveState()
        webContainer.removeAllViews()

        when {
            // The floating window already hosts the current tab
            OverlayManager.mode == OverlayManager.Mode.FLOATING ->
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
            R.id.m_floating -> startFloating()
            R.id.m_keep_alive -> toggleKeepAlive()
            R.id.m_desktop -> TabManager.setDesktop(tab, !tab.desktop)
            R.id.m_share -> shareUrl(tab.url)
            R.id.m_engine -> chooseEngine()
            R.id.m_clear -> confirmClear()
        }
    }

    private fun startFloating() {
        if (!OverlayManager.canDraw()) {
            requestOverlayPermission()
            return
        }
        KeepAliveService.start(this)
        webContainer.removeAllViews()
        if (OverlayManager.showFloating()) {
            moveTaskToBack(true)
        } else {
            attachCurrent()
            toast("Could not open the floating window")
        }
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

    private fun chooseEngine() {
        val names = Prefs.ENGINES.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Search engine")
            .setSingleChoiceItems(names, Prefs.engineIndex) { dialog, which ->
                Prefs.engineIndex = which
                dialog.dismiss()
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

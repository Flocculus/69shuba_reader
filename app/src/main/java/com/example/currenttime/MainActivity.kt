package com.example.currenttime

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var webContainer: FrameLayout
    private lateinit var statusTextView: TextView
    private lateinit var windowInfoTextView: TextView
    private lateinit var einkSwitch: SwitchCompat
    private lateinit var tapPagingSwitch: SwitchCompat
    private lateinit var windowPrevButton: Button
    private lateinit var windowNextButton: Button
    private lateinit var windowNewButton: Button
    private lateinit var windowCloseButton: Button

    private var einkModeEnabled = true
    private var tapPagingEnabled = true

    private val tapSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    private val homeUrl = "https://www.69shuba.com/"
    private val allowedHosts = setOf("69shuba.com", "www.69shuba.com", "m.69shuba.com")
    private val allowedNavTexts = listOf("上一章", "目录", "下一章")

    private val windows = mutableListOf<ReaderWindow>()
    private var currentWindowIndex = -1
    private var nextWindowId = 1

    private data class ReaderWindow(
        val id: Int,
        val webView: WebView,
        var lastUrl: String,
        val allowedChapterTargets: MutableSet<String> = mutableSetOf()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        bindActions()
        initializeWindows()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val webView = currentWebView()
                if (webView != null && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    private fun bindViews() {
        webContainer = findViewById(R.id.webContainer)
        statusTextView = findViewById(R.id.tvStatus)
        windowInfoTextView = findViewById(R.id.tvWindowInfo)
        einkSwitch = findViewById(R.id.switchEink)
        tapPagingSwitch = findViewById(R.id.switchTapPaging)
        windowPrevButton = findViewById(R.id.btnWindowPrev)
        windowNextButton = findViewById(R.id.btnWindowNext)
        windowNewButton = findViewById(R.id.btnWindowNew)
        windowCloseButton = findViewById(R.id.btnWindowClose)
    }

    private fun bindActions() {
        findViewById<Button>(R.id.btnHome).setOnClickListener {
            currentWebView()?.loadUrl(homeUrl)
        }

        findViewById<Button>(R.id.btnPrevChapter).setOnClickListener {
            clickChapterNav("上一章")
        }

        findViewById<Button>(R.id.btnNextChapter).setOnClickListener {
            clickChapterNav("下一章")
        }

        findViewById<Button>(R.id.btnRefresh).setOnClickListener {
            currentWebView()?.reload()
        }

        windowPrevButton.setOnClickListener {
            switchWindow(-1)
        }

        windowNextButton.setOnClickListener {
            switchWindow(1)
        }

        windowNewButton.setOnClickListener {
            createNewWindow()
        }

        windowCloseButton.setOnClickListener {
            closeCurrentWindow()
        }

        einkSwitch.isChecked = einkModeEnabled
        tapPagingSwitch.isChecked = tapPagingEnabled
        tapPagingSwitch.isEnabled = einkModeEnabled

        einkSwitch.setOnCheckedChangeListener { _, isChecked ->
            einkModeEnabled = isChecked
            tapPagingSwitch.isEnabled = isChecked
            updateBrowserPolicyForAllWindows()
            applyEinkModeForAllWindows()
            syncStatusForCurrentWindow()
        }

        tapPagingSwitch.setOnCheckedChangeListener { _, isChecked ->
            tapPagingEnabled = isChecked
            syncStatusForCurrentWindow()
        }
    }

    private fun initializeWindows() {
        windows.clear()
        webContainer.removeAllViews()
        nextWindowId = 1
        currentWindowIndex = -1
        val first = createReaderWindow(homeUrl)
        windows.add(first)
        switchToWindow(0)
    }

    private fun createReaderWindow(initialUrl: String): ReaderWindow {
        val webView = WebView(this)
        webView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        webView.visibility = View.GONE

        val window = ReaderWindow(
            id = nextWindowId++,
            webView = webView,
            lastUrl = initialUrl
        )

        configureWebView(window)
        webContainer.addView(webView)
        webView.loadUrl(initialUrl)

        return window
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(window: ReaderWindow) {
        val webView = window.webView

        webView.setBackgroundColor(Color.WHITE)
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.setOnLongClickListener { einkModeEnabled }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
        }
        updateBrowserPolicy(webView)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message
            ): Boolean {
                if (einkModeEnabled) {
                    return false
                }
                return super.onCreateWindow(view, isDialog, isUserGesture, resultMsg)
            }

            override fun onJsAlert(
                view: WebView,
                url: String,
                message: String,
                result: JsResult
            ): Boolean {
                if (einkModeEnabled) {
                    result.cancel()
                    return true
                }
                return super.onJsAlert(view, url, message, result)
            }

            override fun onJsConfirm(
                view: WebView,
                url: String,
                message: String,
                result: JsResult
            ): Boolean {
                if (einkModeEnabled) {
                    result.cancel()
                    return true
                }
                return super.onJsConfirm(view, url, message, result)
            }

            override fun onJsPrompt(
                view: WebView,
                url: String,
                message: String,
                defaultValue: String,
                result: JsPromptResult
            ): Boolean {
                if (einkModeEnabled) {
                    result.cancel()
                    return true
                }
                return super.onJsPrompt(view, url, message, defaultValue, result)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!einkModeEnabled) {
                    return false
                }

                val target = normalizeUrl(request.url.toString())
                if (!request.isForMainFrame) {
                    return !isAllowedHost(target)
                }

                if (!isAllowedHost(target)) {
                    if (isCurrentWindow(window)) {
                        updateStatusText("已拦截站外链接", window.allowedChapterTargets.size)
                    }
                    return true
                }

                if (isHomeUrl(target) || window.allowedChapterTargets.contains(target)) {
                    return false
                }

                if (isCurrentWindow(window)) {
                    updateStatusText("仅允许: 上一章 / 目录 / 下一章", window.allowedChapterTargets.size)
                }
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (!einkModeEnabled) {
                    return null
                }

                val target = normalizeUrl(request.url.toString())
                return if (isAllowedHost(target)) {
                    null
                } else {
                    blockedResponse()
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                window.lastUrl = url
                if (einkModeEnabled) {
                    window.allowedChapterTargets.clear()
                }
                if (isCurrentWindow(window)) {
                    updateStatusText("加载中...", window.allowedChapterTargets.size)
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                window.lastUrl = url
                applyEinkMode(view)
                if (einkModeEnabled) {
                    enforceNavLinksOnly(window)
                } else {
                    window.allowedChapterTargets.clear()
                    clearNavLinkLock(view)
                    if (isCurrentWindow(window)) {
                        updateStatusText(url, 0)
                    }
                }
            }
        }

        webView.setOnTouchListener(object : View.OnTouchListener {
            var downX = 0f
            var downY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                if (!(einkModeEnabled && tapPagingEnabled && isCurrentWindow(window))) {
                    return false
                }

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                        return false
                    }

                    MotionEvent.ACTION_MOVE -> return true

                    MotionEvent.ACTION_UP -> {
                        val dx = abs(event.x - downX)
                        val dy = abs(event.y - downY)
                        val isTap = dx <= tapSlop && dy <= tapSlop
                        if (!isTap) {
                            return true
                        }

                        val width = v.width.toFloat()
                        val leftEdge = width * 0.25f
                        val rightEdge = width * 0.75f

                        when {
                            event.x <= leftEdge -> {
                                pageBy(window.webView, -1)
                                return true
                            }

                            event.x >= rightEdge -> {
                                pageBy(window.webView, 1)
                                return true
                            }

                            else -> return false
                        }
                    }

                    else -> return false
                }
            }
        })
    }

    private fun currentWindow(): ReaderWindow? {
        return if (currentWindowIndex in windows.indices) windows[currentWindowIndex] else null
    }

    private fun currentWebView(): WebView? {
        return currentWindow()?.webView
    }

    private fun isCurrentWindow(window: ReaderWindow): Boolean {
        return currentWindow()?.id == window.id
    }

    private fun switchWindow(step: Int) {
        if (windows.size <= 1) return
        val size = windows.size
        val nextIndex = (currentWindowIndex + step + size) % size
        switchToWindow(nextIndex)
    }

    private fun switchToWindow(index: Int) {
        if (index !in windows.indices) return

        if (currentWindowIndex in windows.indices) {
            windows[currentWindowIndex].webView.visibility = View.GONE
        }

        currentWindowIndex = index
        val active = windows[currentWindowIndex]
        active.webView.visibility = View.VISIBLE
        active.webView.requestFocus()

        updateWindowControls()
        syncStatusForCurrentWindow()
    }

    private fun createNewWindow() {
        val insertIndex = if (currentWindowIndex in windows.indices) currentWindowIndex + 1 else windows.size
        val newWindow = createReaderWindow(homeUrl)
        windows.add(insertIndex, newWindow)
        switchToWindow(insertIndex)
    }

    private fun closeCurrentWindow() {
        if (windows.isEmpty()) return

        if (windows.size == 1) {
            val only = windows[0]
            only.allowedChapterTargets.clear()
            only.lastUrl = homeUrl
            only.webView.loadUrl(homeUrl)
            updateWindowControls()
            syncStatusForCurrentWindow()
            return
        }

        val removed = windows.removeAt(currentWindowIndex)
        webContainer.removeView(removed.webView)
        removed.webView.stopLoading()
        removed.webView.destroy()

        val nextIndex = if (currentWindowIndex >= windows.size) windows.lastIndex else currentWindowIndex
        currentWindowIndex = -1
        switchToWindow(nextIndex)
    }

    private fun updateWindowControls() {
        if (windows.isEmpty()) {
            windowInfoTextView.text = "窗口 0/0"
            windowPrevButton.isEnabled = false
            windowNextButton.isEnabled = false
            windowCloseButton.isEnabled = false
            return
        }

        val currentNo = currentWindowIndex + 1
        val currentId = currentWindow()?.id ?: 0
        windowInfoTextView.text = "窗口 $currentNo/${windows.size} #$currentId"
        windowPrevButton.isEnabled = windows.size > 1
        windowNextButton.isEnabled = windows.size > 1
        windowCloseButton.isEnabled = true
    }

    private fun syncStatusForCurrentWindow() {
        val window = currentWindow() ?: return
        val url = window.webView.url ?: window.lastUrl
        val count = if (einkModeEnabled) window.allowedChapterTargets.size else 0
        updateStatusText(url, count)
    }

    private fun pageBy(webView: WebView, direction: Int) {
        val js = "window.scrollBy(0, Math.floor(window.innerHeight * 0.90) * $direction);"
        webView.evaluateJavascript(js, null)
    }

    private fun clickChapterNav(label: String) {
        val window = currentWindow() ?: return
        val webView = window.webView
        val script = """
            (function(){
              var label = "$label";
              var links = Array.prototype.slice.call(document.querySelectorAll('a[href]'));
              for (var i = 0; i < links.length; i++) {
                var a = links[i];
                var text = (a.textContent || '').replace(/\s+/g, '').trim();
                if (text === label || text.indexOf(label) >= 0) {
                  a.click();
                  return 'ok';
                }
              }
              return 'miss';
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) { result ->
            if (result?.contains("ok") != true && isCurrentWindow(window)) {
                updateStatusText("当前页未找到$label", window.allowedChapterTargets.size)
            }
        }
    }

    private fun applyEinkModeForAllWindows() {
        windows.forEach { window ->
            val webView = window.webView
            applyEinkMode(webView)
            if (einkModeEnabled) {
                enforceNavLinksOnly(window)
            } else {
                window.allowedChapterTargets.clear()
                clearNavLinkLock(webView)
            }
        }
    }

    private fun applyEinkMode(webView: WebView) {
        if (einkModeEnabled) {
            val script = """
                (function(){
                  var styleId = 'native-eink-style';
                  var style = document.getElementById(styleId);
                  if(!style){
                    style = document.createElement('style');
                    style.id = styleId;
                    document.head.appendChild(style);
                  }
                  style.textContent = '*{animation:none !important;transition:none !important;scroll-behavior:auto !important;} html,body{background:#fff !important;color:#000 !important;} img,video,canvas,svg{filter:grayscale(100%) contrast(120%) !important;}';
                })();
            """.trimIndent()
            webView.evaluateJavascript(script, null)
            webView.setBackgroundColor(Color.WHITE)
            webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        } else {
            val script = """
                (function(){
                  var style = document.getElementById('native-eink-style');
                  if(style){style.remove();}
                })();
            """.trimIndent()
            webView.evaluateJavascript(script, null)
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
    }

    private fun updateBrowserPolicyForAllWindows() {
        windows.forEach { updateBrowserPolicy(it.webView) }
    }

    private fun updateBrowserPolicy(webView: WebView) {
        webView.settings.javaScriptCanOpenWindowsAutomatically = !einkModeEnabled
        webView.settings.setSupportMultipleWindows(!einkModeEnabled)
    }

    private fun enforceNavLinksOnly(window: ReaderWindow) {
        val webView = window.webView
        val labelsJson = allowedNavTexts.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
        val script = """
            (function(){
              var labels = $labelsJson;
              var links = Array.prototype.slice.call(document.querySelectorAll('a[href]'));
              var allowed = [];
              links.forEach(function(a){
                var text = (a.textContent || '').replace(/\s+/g, '').trim();
                var ok = labels.some(function(label){
                  return text === label || text.indexOf(label) >= 0;
                });
                if (ok) {
                  a.setAttribute('data-ink-nav', '1');
                  allowed.push(a.href);
                } else {
                  a.setAttribute('data-ink-nav', '0');
                  a.removeAttribute('target');
                }
              });

              var styleId = 'native-link-lock-style';
              var style = document.getElementById(styleId);
              if (!style) {
                style = document.createElement('style');
                style.id = styleId;
                document.head.appendChild(style);
              }
              style.textContent = 'a[data-ink-nav="0"]{pointer-events:none !important;color:#666 !important;text-decoration:none !important;}';
              return allowed;
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) { raw ->
            window.allowedChapterTargets.clear()
            if (!raw.isNullOrBlank() && raw != "null") {
                try {
                    val array = JSONArray(raw)
                    for (i in 0 until array.length()) {
                        val item = array.optString(i)
                        if (item.isNotBlank() && isAllowedHost(item)) {
                            window.allowedChapterTargets.add(normalizeUrl(item))
                        }
                    }
                } catch (_: Exception) {
                }
            }

            if (isCurrentWindow(window)) {
                updateStatusText(webView.url ?: window.lastUrl, window.allowedChapterTargets.size)
            }
        }
    }

    private fun clearNavLinkLock(webView: WebView) {
        val script = """
            (function(){
              var style = document.getElementById('native-link-lock-style');
              if (style) { style.remove(); }
              var links = document.querySelectorAll('a[data-ink-nav]');
              links.forEach(function(a){
                a.removeAttribute('data-ink-nav');
              });
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun isHomeUrl(url: String): Boolean {
        return normalizeUrl(url) == normalizeUrl(homeUrl)
    }

    private fun isAllowedHost(url: String): Boolean {
        val host = Uri.parse(url).host?.lowercase(Locale.US) ?: return false
        return allowedHosts.any { host == it || host.endsWith(".$it") }
    }

    private fun normalizeUrl(url: String): String {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase(Locale.US) ?: "https"
        val host = uri.host?.lowercase(Locale.US) ?: return url
        val port = uri.port
        val path = uri.path?.ifBlank { "/" } ?: "/"

        val builder = StringBuilder()
        builder.append(scheme).append("://").append(host)
        if (port != -1 && !isDefaultPort(scheme, port)) {
            builder.append(':').append(port)
        }
        builder.append(path)

        val query = uri.query
        if (!query.isNullOrBlank()) {
            builder.append('?').append(query)
        }

        return builder.toString()
    }

    private fun isDefaultPort(scheme: String, port: Int): Boolean {
        return (scheme == "https" && port == 443) || (scheme == "http" && port == 80)
    }

    private fun blockedResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            403,
            "Forbidden",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(ByteArray(0))
        )
    }

    private fun updateStatusText(url: String, allowedCount: Int) {
        if (einkModeEnabled) {
            val paging = if (tapPagingEnabled) "点按翻页:开" else "点按翻页:关"
            statusTextView.text = "墨水优化:开 | $paging | 可点章节导航:$allowedCount\n$url"
        } else {
            statusTextView.text = "普通浏览模式\n$url"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        windows.forEach { window ->
            window.webView.stopLoading()
            window.webView.destroy()
        }
        windows.clear()
    }
}

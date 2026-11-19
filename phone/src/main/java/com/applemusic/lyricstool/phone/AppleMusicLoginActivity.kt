package com.applemusic.lyricstool.phone

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.applemusic.lyricstool.data.AppleLyricsClient

class AppleMusicLoginActivity : ComponentActivity() {
    private val client by lazy { AppleLyricsClient(applicationContext) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView

    private var popupDialog: Dialog? = null
    private var popupWebView: WebView? = null
    private var loginComplete = false

    private val loadTimeout = Runnable {
        if (!loginComplete && !isFinishing && !isDestroyed && ::webView.isInitialized && webView.progress < 100) {
            progressBar.visibility = View.GONE
            statusText.text = "页面加载超时。请检查网络后点“重新加载”。"
        }
    }

    /** Apple Music 网页端通过 SPA XHR 写入 media-user-token，定时轮询 Cookie 可即时捕获完成登录。 */
    private val cookiePoller = object : Runnable {
        override fun run() {
            if (loginComplete || isFinishing || isDestroyed) return
            readLoginCookie()
            if (!loginComplete) {
                mainHandler.postDelayed(this, COOKIE_POLL_INTERVAL_MS)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(BG_COLOR, BG_COLOR),
            navigationBarStyle = SystemBarStyle.light(BG_COLOR, BG_COLOR)
        )
        super.onCreate(savedInstanceState)

        val baseH = dp(18)
        val baseTop = dp(14)
        val baseBottom = dp(10)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(baseH, baseTop, baseH, baseBottom)
            setBackgroundColor(BG_COLOR)
        }

        // Android 15/16 Edge-to-Edge 安全避让系统状态栏与底部导航栏
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(
                baseH + bars.left,
                baseTop + bars.top,
                baseH + bars.right,
                baseBottom + bars.bottom
            )
            insets
        }

        root.addView(
            TextView(this).apply {
                text = "连接 Apple Music"
                textSize = 22f
                setTextColor(Color.rgb(37, 29, 50))
                setTypeface(typeface, Typeface.BOLD)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            TextView(this).apply {
                text = "请在下方页面登录。登录成功后凭据将保存在本机用于同步给手表。"
                textSize = 14f
                setTextColor(Color.rgb(81, 75, 91))
                setPadding(0, dp(5), 0, dp(5))
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        statusText = TextView(this).apply {
            text = "正在打开 Apple Music…"
            textSize = 13f
            setTextColor(Color.rgb(103, 80, 164))
            setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(
            statusText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        root.addView(
            progressBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(3)
            )
        )

        webView = WebView(this).apply {
            configureWebView(this)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progressBar.progress = newProgress
                    progressBar.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
                }

                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?
                ): Boolean {
                    val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                    closePopupDialog()
                    val childWebView = WebView(this@AppleMusicLoginActivity).apply {
                        configureWebView(this)
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(v: WebView?, url: String?) {
                                super.onPageFinished(v, url)
                                readLoginCookie()
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onCloseWindow(window: WebView?) {
                                closePopupDialog()
                                readLoginCookie()
                            }
                        }
                    }
                    val dialog = Dialog(this@AppleMusicLoginActivity).apply {
                        setContentView(
                            childWebView,
                            ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        )
                        window?.setLayout(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            (resources.displayMetrics.heightPixels * 0.82f).toInt()
                        )
                        setOnDismissListener {
                            popupWebView?.apply {
                                stopLoading()
                                destroy()
                            }
                            popupWebView = null
                            popupDialog = null
                            readLoginCookie()
                        }
                    }
                    popupWebView = childWebView
                    popupDialog = dialog
                    dialog.show()
                    transport.webView = childWebView
                    resultMsg.sendToTarget()
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    statusText.text = "正在连接 Apple Music…"
                    progressBar.visibility = View.VISIBLE
                    mainHandler.removeCallbacks(loadTimeout)
                    mainHandler.postDelayed(loadTimeout, LOAD_TIMEOUT_MS)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    mainHandler.removeCallbacks(loadTimeout)
                    progressBar.visibility = View.GONE
                    if (!loginComplete) {
                        statusText.text = "请登录 Apple Music；登录成功后会自动返回。"
                        readLoginCookie()
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        mainHandler.removeCallbacks(loadTimeout)
                        progressBar.visibility = View.GONE
                        statusText.text =
                            "页面加载失败：${error?.description ?: "网络错误"}。请检查网络后重新加载。"
                    }
                }
            }
        }
        root.addView(
            webView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                topMargin = dp(6)
                bottomMargin = dp(8)
            }
        )

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        actions.addView(
            Button(this).apply {
                text = "重新加载"
                setOnClickListener {
                    statusText.text = "正在连接 Apple Music…"
                    progressBar.visibility = View.VISIBLE
                    webView.loadUrl(LOGIN_URL)
                }
            }
        )
        actions.addView(
            Button(this).apply {
                text = "关闭"
                setOnClickListener { finish() }
            }
        )
        root.addView(
            actions,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(root)
        webView.loadUrl(LOGIN_URL)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(target: WebView) {
        target.setBackgroundColor(Color.WHITE)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(target, true)
        target.settings.javaScriptEnabled = true
        target.settings.domStorageEnabled = true
        target.settings.javaScriptCanOpenWindowsAutomatically = true
        target.settings.setSupportMultipleWindows(true)
        target.settings.userAgentString = DESKTOP_CHROME_UA
    }

    private fun closePopupDialog() {
        val dlg = popupDialog
        popupDialog = null
        try {
            dlg?.dismiss()
        } catch (_: Exception) {
        }
        popupWebView?.let { child ->
            popupWebView = null
            try {
                child.stopLoading()
                child.destroy()
            } catch (_: Exception) {
            }
        }
    }

    private fun readLoginCookie() {
        if (loginComplete || isFinishing || isDestroyed) return
        val cookieManager = CookieManager.getInstance()
        val urlsToCheck = LinkedHashSet<String>(COOKIE_URLS.size + 2).apply {
            addAll(COOKIE_URLS)
            if (::webView.isInitialized) {
                webView.url?.takeIf { it.startsWith("https://") }?.let(::add)
            }
            popupWebView?.url?.takeIf { it.startsWith("https://") }?.let(::add)
        }
        val token = urlsToCheck.asSequence()
            .mapNotNull(cookieManager::getCookie)
            .map(client::extractUserToken)
            .firstOrNull { it.length >= 50 }
            ?: return

        loginComplete = true
        mainHandler.removeCallbacks(loadTimeout)
        mainHandler.removeCallbacks(cookiePoller)
        closePopupDialog()
        client.saveUserToken(token)
        cookieManager.flush()
        statusText.text = "登录成功，正在返回…"
        setResult(Activity.RESULT_OK)
        finish()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
        popupWebView?.onResume()
        if (!loginComplete) {
            mainHandler.removeCallbacks(cookiePoller)
            mainHandler.postDelayed(cookiePoller, COOKIE_POLL_INTERVAL_MS)
        }
    }

    override fun onPause() {
        mainHandler.removeCallbacks(cookiePoller)
        popupWebView?.onPause()
        if (::webView.isInitialized) webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(loadTimeout)
        mainHandler.removeCallbacks(cookiePoller)
        closePopupDialog()
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val BG_COLOR = Color.rgb(255, 248, 247)
        const val LOGIN_URL = "https://music.apple.com/"
        const val LOAD_TIMEOUT_MS = 30_000L
        const val COOKIE_POLL_INTERVAL_MS = 1_000L
        const val DESKTOP_CHROME_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        val COOKIE_URLS = listOf(
            "https://music.apple.com/",
            "https://music.apple.com/us/browse",
            "https://music.apple.com/cn/browse",
            "https://amp-api.music.apple.com/"
        )
    }
}

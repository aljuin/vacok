package com.messenger.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader

/**
 * Оболочка: показывает веб-интерфейс мессенджера (assets/index.html) во весь экран.
 * Берёт на себя то, что браузер сам не умеет в WebView: микрофон, выбор файлов,
 * системные уведомления, кнопку «Назад», клавиатуру и ссылки наружу.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingWebPermission: PermissionRequest? = null
    private var pendingChatId: String? = null

    private val appHost = "appassets.androidplatform.net"
    private val allowedHosts = mutableSetOf(appHost)

    // Выбор файлов (фото, видео, аудио) для <input type="file">
    private val fileLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            fileCallback?.onReceiveValue(uris)
            fileCallback = null
        }

    // Разрешения для getUserMedia: микрофон (голосовые) и камера (кружки)
    private val mediaPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val req = pendingWebPermission
            pendingWebPermission = null
            if (req != null) grantWebResources(req)
        }

    private fun hasPermission(p: String): Boolean =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun grantWebResources(req: PermissionRequest) {
        val ok = req.resources.filter { r ->
            when (r) {
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> hasPermission(Manifest.permission.RECORD_AUDIO)
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> hasPermission(Manifest.permission.CAMERA)
                else -> false
            }
        }
        if (ok.isEmpty()) req.deny() else req.grant(ok.toTypedArray())
    }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        WebView.setWebContentsDebuggingEnabled(debuggable)

        root = FrameLayout(this)
        setContentView(root)
        applyTheme(true)
        // Отступы под системные панели и клавиатуру: страница сама подстраивается под высоту окна
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        createChannel()
        askNotificationPermission()

        val server = BuildConfig.SERVER_URL.trim().trimEnd('/')
        if (server.isNotEmpty()) Uri.parse(server).host?.let { allowedHosts.add(it) }

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.TRANSPARENT)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true            // localStorage и IndexedDB
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = true
            textZoom = 100
            setSupportZoom(false)
            builtInZoomControls = false
            mixedContentMode =
                if (debuggable) WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                else WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        web.addJavascriptInterface(Bridge(), "AndroidBridge")

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?, request: WebResourceRequest?
            ): WebResourceResponse? = request?.url?.let { loader.shouldInterceptRequest(it) }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return false
                if (u.host == appHost) return false
                // Внешние ссылки открываем в браузере телефона
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, u))
                } catch (_: Exception) {
                }
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                pendingChatId?.let { openChatInPage(it) }
                pendingChatId = null
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                runOnUiThread { recreate() }
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                if (request.origin.host !in allowedHosts) {
                    request.deny()
                    return
                }
                val wanted = request.resources.filter {
                    it == PermissionRequest.RESOURCE_AUDIO_CAPTURE || it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
                }
                if (wanted.isEmpty()) {
                    request.deny()
                    return
                }
                val needed = mutableListOf<String>()
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in wanted && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
                    needed += Manifest.permission.RECORD_AUDIO
                }
                if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in wanted && !hasPermission(Manifest.permission.CAMERA)) {
                    needed += Manifest.permission.CAMERA
                }
                if (needed.isEmpty()) {
                    grantWebResources(request)
                    return
                }
                pendingWebPermission?.deny()
                pendingWebPermission = request
                mediaPermission.launch(needed.toTypedArray())
            }

            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                if (callback == null) return false
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val wantsAudioCapture = params != null && params.isCaptureEnabled &&
                    params.acceptTypes.any { it.startsWith("audio") }
                val intent = if (wantsAudioCapture) {
                    Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION)   // системный диктофон
                } else {
                    params?.createIntent()
                        ?: Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
                }
                return try {
                    fileLauncher.launch(intent)
                    true
                } catch (_: Exception) {
                    fileCallback = null
                    callback.onReceiveValue(null)
                    false
                }
            }
        }

        root.addView(web)

        // Кнопка «Назад»: сначала закрываем экраны внутри страницы, потом сворачиваем приложение
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(function(){return window.__back?window.__back():false})()") { r ->
                    if (r != "true") moveTaskToBack(true)
                }
            }
        })

        pendingChatId = intent?.getStringExtra("chatId")

        val url = "https://$appHost/assets/index.html" +
            if (server.isNotEmpty()) "?server=" + Uri.encode(server) else ""
        web.loadUrl(url)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("chatId")?.let { openChatInPage(it) }
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private fun openChatInPage(id: String) {
        val digits = id.filter { it.isDigit() }
        if (digits.isEmpty()) return
        web.evaluateJavascript("window.__openChat&&window.__openChat('$digits')", null)
    }

    private fun applyTheme(dark: Boolean) {
        root.setBackgroundColor(if (dark) BG_DARK else BG_LIGHT)
        val c = WindowCompat.getInsetsController(window, root)
        c.isAppearanceLightStatusBars = !dark
        c.isAppearanceLightNavigationBars = !dark
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "Сообщения", NotificationManager.IMPORTANCE_HIGH)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun showNotification(title: String, body: String, chatId: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("chatId", chatId)
        }
        val pi = PendingIntent.getActivity(
            this, chatId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        NotificationManagerCompat.from(this).notify(chatId.hashCode(), n)
    }

    /** Мост «страница → приложение». Вызывается из JavaScript как AndroidBridge.метод(...) */
    inner class Bridge {
        @JavascriptInterface
        fun notify(title: String, body: String, chatId: String) {
            showNotification(title, body, chatId)
        }

        @JavascriptInterface
        fun setTheme(dark: Boolean) {
            runOnUiThread { applyTheme(dark) }
        }

        /** Текущая версия установленного приложения (versionCode) */
        @JavascriptInterface
        fun appVersionCode(): Int = BuildConfig.VERSION_CODE

        /** Скачать и установить новую версию APK по адресу с нашего сервера */
        @JavascriptInterface
        fun installUpdate(url: String) {
            runOnUiThread { UpdateInstaller.start(this@MainActivity, url, web) }
        }
    }

    companion object {
        private const val CHANNEL = "messages"
        private val BG_DARK = Color.parseColor("#15161B")
        private val BG_LIGHT = Color.parseColor("#EEF1F6")
    }
}

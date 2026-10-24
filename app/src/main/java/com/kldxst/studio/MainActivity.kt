package com.kldxst.studio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.kldxst.studio.console.ConsoleBridge
import com.kldxst.studio.core.Hub
import com.kldxst.studio.service.StudioService
import org.json.JSONObject
import java.security.SecureRandom

/**
 * 唯一的 Activity：一个 WebView 装着整个控制台。
 *
 * 「只打包一次」的技术前提就在这里 —— 界面、流程、作品清单、协议参数全部来自
 * [BuildConfig.CONSOLE_URL]，APK 只负责三件不会变的事：
 *   1. 与手环通信（官方 SDK 通道）
 *   2. 代快应用发 HTTP（网桥引擎）
 *   3. 安全地把能力暴露给页面（本类 + ConsoleBridge）
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var bridge: ConsoleBridge
    private val token: String = randomToken()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val denied = result.filterValues { !it }.keys
            if (denied.isNotEmpty()) {
                Hub.log.add("权限未授予：${denied.joinToString()}")
            }
            // 权限对话框关闭后再决定是否拉起前台服务，避免 Android 14 拒绝无权限的 connectedDevice 服务。
            StudioService.startIfEnabled(this)
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        val hosts = BuildConfig.CONSOLE_HOSTS.split(",").map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }.toSet()
        bridge = ConsoleBridge(
            this,
            hosts,
            token,
            onRequestPermissions = { runOnUiThread { requestAppPermissions() } },
            onOpenExternal = { url -> runOnUiThread { openExternal(url) } },
            onOpenNotificationSettings = {
                runOnUiThread {
                    runCatching {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                }
            },
            onOpenAppSettings = {
                runOnUiThread {
                    runCatching {
                        startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:$packageName")
                            )
                        )
                    }
                }
            }
        )
        bridge.emit = { id, payload ->
            web.post {
                val js = "window.KldxstOnResult&&window.KldxstOnResult(" +
                    JSONObject.quote(id) + "," + JSONObject.quote(payload) + ")"
                web.evaluateJavascript(js, null)
            }
        }

        web = WebView(this).apply {
            setBackgroundColor(0xFFF5F7FB.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportMultipleWindows(false)
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mediaPlaybackRequiresUserGesture = true
            settings.saveFormData = false
            settings.savePassword = false
            settings.userAgentString = settings.userAgentString + " KldxstStudio/${BuildConfig.VERSION_NAME}"
            addJavascriptInterface(bridge, "Kldxst")
            webViewClient = Client()
        }

        // 页面脚本一执行就需要令牌。onPageFinished 注入太晚，因此优先使用
        // WebView 的 document-start 能力；不支持时页面会通过 Kldxst.token() 取。
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            val script = "window.__kldxstToken=${JSONObject.quote(token)};"
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(
                    web,
                    script,
                    setOf(BuildConfig.CONSOLE_ORIGIN, BuildConfig.ACTIVE_ORIGIN)
                )
            }.onFailure { Hub.log.add("令牌预注入失败：${it.message}") }
        }

        setContentView(web)
        requestAppPermissions()
        web.loadUrl(BuildConfig.CONSOLE_URL)
        Hub.log.add("控制台加载 ${BuildConfig.CONSOLE_URL}")
    }

    override fun onBackPressed() {
        if (this::web.isInitialized && web.canGoBack()) {
            web.goBack()
            return
        }
        super.onBackPressed()
    }

    private fun requestAppPermissions() {
        val missing = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!granted(Manifest.permission.BLUETOOTH_SCAN)) missing.add(Manifest.permission.BLUETOOTH_SCAN)
            if (!granted(Manifest.permission.BLUETOOTH_CONNECT)) missing.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R &&
            !granted(Manifest.permission.ACCESS_FINE_LOCATION)
        ) {
            // Android 11 及以下，BLE 扫描仍按定位权限授权。
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !granted(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isEmpty()) {
            StudioService.startIfEnabled(this)
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun openExternal(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" && uri.scheme != "http") return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .onFailure { Hub.log.add("打开外部链接失败：${it.message}") }
    }

    private inner class Client : WebViewClient() {

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            bridge.currentOrigin = originOf(url)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            bridge.currentOrigin = originOf(url)
            // 令牌只在白名单页面注入；其他页面拿不到，也就调不动原生能力
            if (isAllowed(url)) {
                view.evaluateJavascript("window.__kldxstToken=${JSONObject.quote(token)};", null)
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            return if (isAllowed(url)) {
                false
            } else {
                if (request.isForMainFrame) openExternal(url)
                true
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            super.onReceivedError(view, request, error)
            if (!request.isForMainFrame) return
            Hub.log.add("控制台加载失败：${error.description}")
            showOffline(error.description?.toString() ?: "未知错误")
        }
    }

    /** 控制台不可达时的本地兜底页：说清楚缺什么，并提供重试/诊断 */
    private fun showOffline(reason: String) {
        val bluetooth = if (Hub.wear.available()) "蓝牙已开启" else "蓝牙未开启"
        val saved = escapeHtml(com.kldxst.studio.secure.Vault.get("device.address") ?: "未保存设备")
        val safeReason = escapeHtml(reason)
        val html = """
            <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
            <style>
              :root{color-scheme:light}
              body{margin:0;padding:24px;background:#F5F7FB;color:#141821;
                   font:15px/1.6 -apple-system,"PingFang SC","Noto Sans CJK SC",sans-serif}
              h1{font-size:19px;margin:0 0 8px;color:#2563EB}
              .card{background:#FFFFFF;border:1px solid #E2E6EF;border-radius:14px;padding:16px;margin:14px 0}
              code{color:#6B7280;font-size:12px;word-break:break-all}
              button{width:100%;padding:13px;margin-top:10px;border-radius:12px;border:1px solid #E2E6EF;
                     background:#FFFFFF;color:#141821;font-size:15px}
              button.primary{background:#2563EB;color:#FFFFFF;border-color:#2563EB;font-weight:600}
              ul{padding-left:20px;margin:6px 0}
            </style></head><body>
            <h1>控制台暂时连不上</h1>
            <div class="card">$safeReason</div>
            <div class="card"><b>本机检查</b><ul><li>$bluetooth</li><li>上次连接：$saved</li></ul>
              <code>${BuildConfig.CONSOLE_URL}</code></div>
            <div class="card"><b>接下来</b><br>
              1. 确认网络可用（控制台只走 HTTPS）<br>
              2. 装好小米运动健康并连接手环<br>
              3. 点下面重试</div>
            <button class="primary" onclick="location.replace('${BuildConfig.CONSOLE_URL}')">重试</button>
            <button onclick="window.__kldxstToken&&alert('已注入会话令牌，可继续使用')">检查会话</button>
            </body></html>
        """.trimIndent()
        // 离线页的 base URL 是控制台域名；显式同步 origin，让“检查会话”按钮仍能走令牌校验。
        bridge.currentOrigin = BuildConfig.CONSOLE_ORIGIN
        web.loadDataWithBaseURL(BuildConfig.CONSOLE_ORIGIN, html, "text/html", "utf-8", null)
    }

    private fun isAllowed(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val u = Uri.parse(url)
        if (!u.scheme.equals("https", ignoreCase = true)) return false
        if (u.port != -1 && u.port != 443) return false
        val host = u.host?.lowercase() ?: return false
        return BuildConfig.CONSOLE_HOSTS.split(",").map { it.trim().lowercase() }.contains(host)
    }

    private fun originOf(url: String?): String {
        if (url == null) return ""
        val u = Uri.parse(url)
        val scheme = u.scheme?.lowercase() ?: return ""
        val host = u.host?.lowercase() ?: return ""
        val port = if (u.port == -1 || (scheme == "https" && u.port == 443) || (scheme == "http" && u.port == 80)) {
            ""
        } else {
            ":${u.port}"
        }
        return "$scheme://$host$port"
    }

    private fun randomToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    override fun onDestroy() {
        if (this::web.isInitialized) {
            web.loadUrl("about:blank")
            web.destroy()
        }
        super.onDestroy()
    }

    @Suppress("unused")
    private fun noop(v: View) = Unit
}

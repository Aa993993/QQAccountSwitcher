package com.qqswitcher.demo

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

/**
 * QQ 上号器（HTTP Server 版）
 *
 * 流程：
 * 1. App 启动后开启 HTTP 端口 8888
 * 2. PC 前端扫 QQ 二维码拿到 token → POST 到 http://雷电IP:8888/inject
 * 3. App 注入 Cookie → 拉起游戏
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val HTTP_PORT = 8888
        private const val GAME_PACKAGE = "com.tencent.tmgp.sgame"
        private val QQ_DOMAINS = listOf(
            "https://qq.com", "https://.qq.com",
            "https://connect.qq.com", "https://.connect.qq.com",
            "https://openmobile.qq.com", "https://.openmobile.qq.com",
            "https://ptlogin4.qq.com", "https://.ptlogin4.qq.com",
            "https://ui.ptlogin2.qq.com"
        )
    }

    private lateinit var logPanel: TextView
    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var ipText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var serverSocket: ServerSocket? = null
    private var serverRunning = false

    // ============================================================
    // Lifecycle
    // ============================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logPanel = findViewById(R.id.logPanel)
        statusText = findViewById(R.id.statusText)
        statusDot = findViewById(R.id.statusDot)
        ipText = findViewById(R.id.ipText)

        log("🦞 QQ 上号器启动")

        initWebView()
        startHttpServer()
        showLocalIp()
    }

    override fun onDestroy() {
        serverRunning = false
        serverSocket?.close()
        webView?.destroy()
        super.onDestroy()
    }

    // ============================================================
    // WebView
    // ============================================================
    private fun initWebView() {
        val wv = WebView(this)
        wv.apply {
            layoutParams = ViewGroup.LayoutParams(1, 1)
            visibility = View.GONE

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                userAgentString = WebSettings.getDefaultUserAgent(this@MainActivity)
                allowContentAccess = true
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = false
                displayZoomControls = false
                allowFileAccess = false
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    log("🌐 加载: ${url?.take(100)}...")
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    log("✅ 页面完成: ${url?.take(80)}...")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        CookieManager.getInstance().flush()
                    }
                    // 认证成功 → 拉游戏
                    if (url?.contains("connect.qq.com") == true) {
                        log("🎉 认证成功，准备上游戏!")
                        launchGame()
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    if (url.startsWith("tencent") || url.startsWith("com.tencent")) {
                        log("🎮 游戏协议: ${url.take(60)}...")
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                            return true
                        } catch (e: Exception) {
                            launchGame()
                            return true
                        }
                    }
                    return url.contains("qq.com") || url.contains("tencent.com")
                }
            }

            CookieManager.getInstance().setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
            }

            (window.decorView as ViewGroup).addView(this)
        }
        webView = wv
        log("🔧 WebView 就绪（隐藏）")
    }

    // ============================================================
    // HTTP Server
    // ============================================================
    private fun startHttpServer() {
        serverRunning = true
        thread(name = "HttpServer") {
            try {
                serverSocket = ServerSocket(HTTP_PORT)
                log("🌐 HTTP 服务已启动: 端口 $HTTP_PORT")
                log("📮 POST http://你的IP:$HTTP_PORT/inject")
                log("")

                while (serverRunning) {
                    try {
                        val client = serverSocket!!.accept()
                        handleClient(client)
                    } catch (e: Exception) {
                        if (serverRunning) log("⚠️ 连接错误: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                log("❌ HTTP 服务启动失败: ${e.message}")
            }
        }
    }

    private fun handleClient(client: Socket) {
        thread {
            try {
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), "UTF-8"))
                val requestLine = reader.readLine() ?: return@thread
                val parts = requestLine.split(" ")
                if (parts.size < 2) return@thread

                val method = parts[0]
                val path = parts[1]

                // 读取请求头
                var contentLength = 0
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line!!.isBlank()) break
                    if (line!!.lowercase().startsWith("content-length:")) {
                        contentLength = line!!.split(":")[1].trim().toIntOrNull() ?: 0
                    }
                }

                // 读取请求体
                val body = if (contentLength > 0) {
                    val buffer = CharArray(contentLength)
                    reader.read(buffer, 0, contentLength)
                    String(buffer)
                } else ""

                val responseBody: String
                val statusCode: String

                when {
                    path == "/inject" && method == "POST" -> {
                        log("📥 收到 token: ${body.take(100)}...")
                        val result = processToken(body)
                        responseBody = "{\"code\":0,\"msg\":\"$result\"}"
                        statusCode = "200 OK"
                    }
                    path == "/" && method == "GET" -> {
                        responseBody = """
                            {"status":"running","port":$HTTP_PORT,"game":"$GAME_PACKAGE"}
                        """.trimIndent()
                        statusCode = "200 OK"
                    }
                    else -> {
                        responseBody = "{\"code\":404}"
                        statusCode = "404 Not Found"
                    }
                }

                val resp = "HTTP/1.1 $statusCode\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Content-Length: ${responseBody.toByteArray(Charsets.UTF_8).size}\r\n" +
                        "Connection: close\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "\r\n" +
                        responseBody

                val writer = OutputStreamWriter(client.getOutputStream(), "UTF-8")
                writer.write(resp)
                writer.flush()
            } catch (e: Exception) {
                Log.e(TAG, "请求处理失败: ${e.message}")
            } finally {
                try { client.close() } catch (_: Exception) {}
            }
        }
    }

    // ============================================================
    // Token Processing
    // ============================================================
    private fun processToken(body: String): String {
        return try {
            // 尝试解析 JSON
            val json = Gson().fromJson(body, TokenPayload::class.java)

            // 1. 清除旧 Cookie
            clearCookies()
            log("🧹 旧 Cookie 已清")

            // 2. 注入 Cookie
            if (!json.cookie.isNullOrBlank()) {
                injectCookies(json.cookie!!)
                log("☑️ Cookie 注入完成")
            }

            // 3. 如果有 authUrl 就加载
            if (!json.authUrl.isNullOrBlank()) {
                handler.post {
                    val wv = webView
                    wv?.loadUrl(json.authUrl!!)
                    log("🚀 加载认证 URL")
                }
                "认证 URL 已加载"
            } else if (!json.cookie.isNullOrBlank()) {
                // 只有 Cookie → 直接开游戏
                handler.post { launchGame() }
                "Cookie 已注入，正在开游戏"
            } else {
                "${json.uin ?: "未知"} 已接收"
            }
        } catch (e: Exception) {
            // 不是 JSON → 可能是纯 Cookie 字符串
            clearCookies()
            injectCookies(body)
            handler.post { launchGame() }
            "纯 Cookie 已注入"
        }
    }

    private fun clearCookies() {
        val cm = CookieManager.getInstance()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cm.removeAllCookies(null)
            cm.flush()
        } else {
            cm.removeAllCookie()
        }
        webView?.clearCache(true)
        webView?.clearHistory()
    }

    private fun injectCookies(cookieStr: String) {
        val cm = CookieManager.getInstance()
        val cookies = cookieStr.split(";").map { it.trim() }.filter { it.isNotBlank() }

        cookies.forEach { cookie ->
            QQ_DOMAINS.forEach { domain ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    cm.setCookie(domain, "$cookie; domain=${domain.removePrefix("https://")}")
                } else {
                    cm.setCookie(domain, cookie)
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cm.flush()
        }
    }

    // ============================================================
    // Launch Game
    // ============================================================
    private fun launchGame() {
        log("🎯 启动游戏: $GAME_PACKAGE")
        setStatus("✅ 正在上号...")
        try {
            val intent = packageManager.getLaunchIntentForPackage(GAME_PACKAGE)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                log("✅ 游戏已拉起")
                setStatus("✅ 已上号")
            } else {
                log("⚠️ 游戏未安装")
                setStatus("❌ 游戏未安装")
            }
        } catch (e: Exception) {
            log("❌ 启动失败: ${e.message}")
        }
    }

    // ============================================================
    // UI
    // ============================================================
    private fun showLocalIp() {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is java.net.Inet4Address) {
                        val ip = addr.hostAddress
                        if (!ip.startsWith("127.")) {
                            handler.post {
                                ipText.text = ip
                            }
                            log("📡 本机IP: $ip")
                            log("📮 POST → http://$ip:$HTTP_PORT/inject")
                            log("")
                            setStatus("等待接收 token...")
                            return
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        handler.post {
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
            logPanel.append("[${sdf.format(Date())}] $msg\n")
            val scrollAmount = logPanel.layout?.getLineTop(logPanel.lineCount) ?: 0
            if (scrollAmount > logPanel.height) {
                logPanel.scrollTo(0, scrollAmount - logPanel.height)
            }
        }
    }

    private fun setStatus(text: String) {
        handler.post { statusText.text = text }
    }

    // ============================================================
    // Data
    // ============================================================
    data class TokenPayload(
        val uin: String? = null,
        val nick: String? = null,
        val cookie: String? = null,
        val authUrl: String? = null,
        val ptsigx: String? = null
    )

    private val TAG = "QQSwitcher"
}

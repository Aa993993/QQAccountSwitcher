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
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

/**
 * QQ 上号器 v2（Token 注入版）
 *
 * 流程：
 * PC 扫码 → 拿到 access_token → POST 到 http://雷电IP:8888/inject
 * → App 写入游戏 QQ SDK 存储 → 拉起王者 → 自动登录
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val HTTP_PORT = 8888
        private const val GAME_PACKAGE = "com.tencent.tmgp.sgame"  // 王者荣耀

        // QQ OAuth 配置（从扫码 URL 提取）
        private const val APP_ID = "716027609"
        private const val THIRD_APP_ID = "1104466820"
        private const val DAID = "381"
    }

    private lateinit var logPanel: TextView
    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var ipText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logPanel = findViewById(R.id.logPanel)
        statusText = findViewById(R.id.statusText)
        statusDot = findViewById(R.id.statusDot)
        ipText = findViewById(R.id.ipText)

        log("🦞 QQ 上号器 v2")
        log("🎯 目标: $GAME_PACKAGE")
        initWebView()
        startHttpServer()
        showLocalIp()
    }

    override fun onDestroy() {
        webView?.destroy()
        super.onDestroy()
    }

    // ============================================================
    // WebView（隐藏，用于 OAuth Cookie 注入）
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
                allowFileAccess = true
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    log("✅ 页面完成: ${url?.take(80)}...")
                    CookieManager.getInstance().flush()
                    if (url?.contains("connect.qq.com") == true) {
                        log("🎉 认证成功，准备上游戏!")
                        launchGame()
                    }
                }
            }

            CookieManager.getInstance().setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
            }
            (window.decorView as ViewGroup).addView(this)
        }
        webView = wv
    }

    // ============================================================
    // HTTP Server → 接收前端 token
    // ============================================================
    private fun startHttpServer() {
        thread(name = "HttpServer") {
            try {
                val serverSocket = ServerSocket(HTTP_PORT)
                log("🌐 HTTP 服务启动: 端口 $HTTP_PORT")
                log("📮 POST http://你的IP:$HTTP_PORT/inject")
                log("")

                while (true) {
                    try {
                        val client = serverSocket.accept()
                        handleClient(client)
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                log("❌ HTTP 服务失败: ${e.message}")
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

                // 读请求头
                var contentLength = 0
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line!!.isBlank()) break
                    if (line!!.lowercase().startsWith("content-length:")) {
                        contentLength = line!!.split(":")[1].trim().toIntOrNull() ?: 0
                    }
                }

                // 读请求体
                val body = if (contentLength > 0) {
                    val buffer = CharArray(contentLength)
                    reader.read(buffer, 0, contentLength)
                    String(buffer)
                } else ""

                val responseBody: String
                val statusCode: String

                when {
                    path == "/inject" && method == "POST" -> {
                        log("📥 收到 token: ${body.take(80)}...")
                        val result = processToken(body)
                        responseBody = "{\"code\":0,\"msg\":\"$result\"}"
                        statusCode = "200 OK"
                    }
                    path == "/" && method == "GET" -> {
                        responseBody = """{"status":"running","port":$HTTP_PORT}"""
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
                        "\r\n" + responseBody

                val writer = OutputStreamWriter(client.getOutputStream(), "UTF-8")
                writer.write(resp)
                writer.flush()
            } catch (_: Exception) {}
            finally { try { client.close() } catch (_: Exception) {} }
        }
    }

    // ============================================================
    // Token 处理核心
    // ============================================================
    private fun processToken(body: String): String {
        return try {
            // 解析 JSON
            val token = Gson().fromJson(body, QQToken::class.java)
            if (token.access_token.isNullOrBlank()) return "缺少 access_token"

            log("👤 QQ号: ${token.openid?.take(10)}...")
            log("🔑 access_token: ${token.access_token?.take(16)}...")

            // === 方案 A: Root 注入 QQ SDK SharedPreferences（推荐）===
            if (injectToSdkPrefs(token)) {
                log("✅ Scheme A: 写入 QQ SDK 存储成功")
                handler.post { launchGame() }
                return "Root 注入成功，启动游戏"
            }

            // === 方案 B: 构造 OAuth 回调 URL → WebView 加载 ===
            log("⚠️ 未获取 Root，尝试 WebView 方案...")
            val authUrl = buildAuthUrl(token)
            log("🔗 加载认证 URL")
            clearCookies()
            handler.post {
                webView?.loadUrl(authUrl)
            }
            "WebView 认证中..."

        } catch (e: Exception) {
            // 不是 JSON → 当纯 Cookie 处理
            log("⚠️ JSON 解析失败，尝试纯 Cookie 注入: ${e.message}")
            clearCookies()
            injectCookies(body)
            handler.post { launchGame() }
            "Cookie 已注入"
        }
    }

    // ============================================================
    // 方案 A: Root 注入 QQ SDK SharedPreferences
    // ============================================================
    private fun injectToSdkPrefs(token: QQToken): Boolean {
        val openid = token.openid ?: return false
        val accessToken = token.access_token ?: return false

        // QQ SDK 存 token 的文件路径（v3+ 版本）
        val prefPaths = listOf(
            // 方式 1: 游戏内置 QQ SDK
            "/data/data/$GAME_PACKAGE/shared_prefs/${APP_ID}_${openid}_${APP_ID}.xml",
            "/data/data/$GAME_PACKAGE/shared_prefs/tencent_auth.xml",
            "/data/data/$GAME_PACKAGE/shared_prefs/${THIRD_APP_ID}_preferences.xml",
            // 方式 2: 系统 QQ 的 SSO 存储
            "/data/data/com.tencent.mobileqq/shared_prefs/qq_sso_preferences.xml",
            "/data/data/com.tencent.mobileqq/shared_prefs/last_login_uin.xml",
        )

        val prefXml = buildPrefXml(token)

        for (path in prefPaths) {
            try {
                // 尝试创建目录
                val dir = path.substringBeforeLast("/")
                execRoot("mkdir -p $dir")
                execRoot("chmod 777 $dir")

                // 写入 token XML
                execRoot("echo '$prefXml' > '$path'")
                execRoot("chmod 666 $path")

                log("📝 写入: $path")
            } catch (_: Exception) {}
        }

        // 验证是否至少成功写入了游戏目录
        val result = execRoot("ls -la /data/data/$GAME_PACKAGE/shared_prefs/")
        log("📂 目录结构:\n${result.take(300)}")

        return result.contains(APP_ID) || result.contains("tencent_auth")
    }

    private fun buildPrefXml(token: QQToken): String {
        val now = System.currentTimeMillis() / 1000
        val expiresAt = now + (token.expires_in?.toLongOrNull() ?: 5184000L)

        return """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="access_token">${token.access_token}</string>
    <string name="expires_in">${token.expires_in ?: "5184000"}</string>
    <string name="openid">${token.openid}</string>
    <string name="pay_token">${token.pay_token ?: ""}</string>
    <string name="pf">${token.pf ?: "openmobile_android"}</string>
    <string name="pfkey">${token.pfkey ?: ""}</string>
    <long name="expires_at" value="$expiresAt" />
    <long name="auth_time" value="${token.auth_time ?: "$now"}" />
    <int name="ret" value="0" />
</map>"""
    }

    // ============================================================
    // 方案 B: OAuth 回调 URL → WebView
    // ============================================================
    private fun buildAuthUrl(token: QQToken): String {
        // 用 access_token 构造 QQ OAuth 回调，让 QQ SDK 识别
        return "https://graph.qq.com/oauth2.0/login?" +
                "access_token=${token.access_token}" +
                "&openid=${token.openid}" +
                "&expires_in=${token.expires_in ?: "5184000"}" +
                "&ret=0" +
                "&s_url=https%3A%2F%2Fconnect.qq.com"
    }

    // ============================================================
    // Cookie 注入（备选方案）
    // ============================================================
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
        val qqDomains = listOf(
            ".qq.com", ".connect.qq.com", ".openmobile.qq.com",
            ".ptlogin4.qq.com", ".ui.ptlogin2.qq.com"
        )
        cookieStr.split(";").map { it.trim() }.filter { it.isNotBlank() }.forEach { cookie ->
            qqDomains.forEach { domain ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    cm.setCookie("https:$domain", "$cookie; domain=$domain")
                } else {
                    cm.setCookie("https:$domain", cookie)
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cm.flush()
        }
    }

    // ============================================================
    // Root Shell
    // ============================================================
    private fun execRoot(cmd: String): String {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            os.writeBytes("$cmd\n")
            os.writeBytes("exit\n")
            os.flush()

            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            process.waitFor()
            output.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Root 执行失败: ${e.message}")
            ""
        }
    }

    // ============================================================
    // Launch Game
    // ============================================================
    private fun launchGame() {
        log("🎯 启动游戏: $GAME_PACKAGE")
        try {
            val intent = packageManager.getLaunchIntentForPackage(GAME_PACKAGE)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                log("✅ 游戏已拉起")
                setStatus("✅ 已上号")
            } else {
                log("⚠️ 游戏未安装")
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
                            handler.post { ipText.text = ip }
                            log("📡 IP: $ip:$HTTP_PORT")
                            log("📮 POST → http://$ip:$HTTP_PORT/inject")
                            setStatus("等待 token...")
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
    // Data Model
    // ============================================================
    data class QQToken(
        val access_token: String? = null,
        val expires_in: String? = "5184000",
        val openid: String? = null,
        val pay_token: String? = null,
        val pf: String? = "openmobile_android",
        val pfkey: String? = null,
        val auth_time: String? = null,
        val state: String? = null,
        val ret: String? = "0"
    )

    private val TAG = "QQSwitcher"
}

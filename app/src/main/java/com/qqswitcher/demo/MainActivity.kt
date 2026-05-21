package com.qqswitcher.demo

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.*
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var logPanel: TextView
    private lateinit var statusText: TextView
    private lateinit var statusDot: TextView
    private lateinit var ipText: TextView
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)
            logPanel = findViewById(R.id.logPanel)
            statusText = findViewById(R.id.statusText)
            statusDot = findViewById(R.id.statusDot)
            ipText = findViewById(R.id.ipText)

            log("🦞 启动...")
            setupWebView()
            startHttpServer()
            showLocalIp()
            log("✅ 就绪")
        } catch (e: Exception) {
            Log.e(TAG, "启动失败", e)
            try { log("❌ 启动失败: ${e.message}") } catch (_: Exception) {}
        }
    }

    // ============================================================
    // WebView
    // ============================================================
    private fun setupWebView() {
        try {
            val wv = findViewById<WebView>(R.id.hiddenWebView)
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                userAgentString = WebSettings.getDefaultUserAgent(this@MainActivity)
            }
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    log("✅ WebView: ${url?.take(60)}...")
                    if (url?.contains("connect.qq.com") == true) {
                        log("🎉 认证成功!")
                        launchGame()
                    }
                }
            }
            CookieManager.getInstance().setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
            }
            log("🔧 WebView OK")
        } catch (e: Exception) {
            log("⚠️ WebView: ${e.message}")
        }
    }

    // ============================================================
    // HTTP Server
    // ============================================================
    private fun startHttpServer() {
        thread(name = "httpd") {
            try {
                val ss = ServerSocket(HTTP_PORT)
                log("🌐 HTTP: 端口 $HTTP_PORT")
                while (true) {
                    try {
                        val c = ss.accept()
                        handleClient(c)
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                log("❌ HTTP: ${e.message}")
            }
        }
    }

    private fun handleClient(c: Socket) {
        thread {
            try {
                val r = BufferedReader(InputStreamReader(c.getInputStream(), "UTF-8"))
                val l = r.readLine() ?: return@thread
                val parts = l.split(" ")
                if (parts.size < 2) return@thread

                var cl = 0
                var line: String?
                while (r.readLine().also { line = it } != null) {
                    if (line!!.isBlank()) break
                    if (line!!.lowercase().startsWith("content-length:"))
                        cl = line!!.split(":")[1].trim().toIntOrNull() ?: 0
                }

                val body = if (cl > 0) { val buf = CharArray(cl); r.read(buf, 0, cl); String(buf) } else ""
                val (code, msg) = if (parts[0] == "POST" && parts[1] == "/inject") {
                    log("📥 Token: ${body.take(80)}...")
                    val result = processToken(body)
                    Pair("200 OK", "{\"code\":0,\"msg\":\"$result\"}")
                } else {
                    Pair("200 OK", """{"status":"ok"}""")
                }

                val resp = "HTTP/1.1 $code\r\nContent-Type: application/json\r\nContent-Length: ${msg.toByteArray().size}\r\nConnection: close\r\nAccess-Control-Allow-Origin: *\r\n\r\n$msg"
                val w = OutputStreamWriter(c.getOutputStream(), "UTF-8")
                w.write(resp)
                w.flush()
            } catch (_: Exception) {}
            finally { try { c.close() } catch (_: Exception) {} }
        }
    }

    // ============================================================
    // Token 处理
    // ============================================================
    private fun processToken(body: String): String {
        return try {
            val t = Gson().fromJson(body, Token::class.java)
            if (t.access_token.isNullOrBlank()) return "缺少 access_token"
            log("👤 openid: ${t.openid?.take(10)}...")
            log("🔑 token: ${t.access_token?.take(16)}...")

            // 方案 A: Root 写游戏 QQ SDK 存储
            if (injectByRoot(t)) {
                handler.post { launchGame() }
                "Root 注入成功"
            }
            // 方案 B: 构造 OAuth URL → WebView
            else {
                log("⚠️ 尝试 WebView 方案")
                clearCookies()
                val url = "https://graph.qq.com/oauth2.0/login?access_token=${t.access_token}&openid=${t.openid}&ret=0&s_url=https%3A%2F%2Fconnect.qq.com"
                handler.post {
                    findViewById<WebView>(R.id.hiddenWebView).loadUrl(url)
                }
                "WebView 认证中..."
            }
        } catch (e: Exception) {
            clearCookies()
            injectCookies(body)
            handler.post { launchGame() }
            "Cookie 注入"
        }
    }

    private fun injectByRoot(t: Token): Boolean {
        val xml = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="access_token">${t.access_token}</string>
    <string name="expires_in">${t.expires_in ?: "5184000"}</string>
    <string name="openid">${t.openid}</string>
    <string name="pay_token">${t.pay_token ?: ""}</string>
    <string name="pf">${t.pf ?: "openmobile_android"}</string>
    <string name="pfkey">${t.pfkey ?: ""}</string>
    <long name="expires_at" value="${System.currentTimeMillis()/1000 + (t.expires_in?.toLongOrNull() ?: 5184000L)}" />
    <int name="ret" value="0" />
</map>"""
        val paths = listOf(
            "/data/data/$GAME_PACKAGE/shared_prefs/${THIRD_APP_ID}_${t.openid}_${THIRD_APP_ID}.xml",
            "/data/data/$GAME_PACKAGE/shared_prefs/${APP_ID}_${t.openid}_${APP_ID}.xml",
            "/data/data/$GAME_PACKAGE/shared_prefs/tencent_auth.xml",
        )
        paths.forEach { path ->
            try {
                val dir = path.substringBeforeLast("/")
                runRoot("mkdir -p $dir")
                runRoot("chmod 777 $dir")
                runRoot("echo '$xml' > '$path'")
                runRoot("chmod 666 $path")
                log("📝 写入: ${path.substringAfterLast("/")}")
            } catch (_: Exception) {}
        }
        val result = runRoot("ls /data/data/$GAME_PACKAGE/shared_prefs/ 2>/dev/null")
        return result.contains("tencent_auth") || result.contains(THIRD_APP_ID)
    }

    private fun runRoot(cmd: String): String {
        return try {
            val p = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(p.outputStream)
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            os.writeBytes("$cmd\n"); os.writeBytes("exit\n"); os.flush()
            val sb = StringBuilder()
            var l: String?; while (reader.readLine().also { l = it } != null) sb.append(l).append("\n")
            p.waitFor()
            sb.toString()
        } catch (e: Exception) { "" }
    }

    private fun clearCookies() {
        val cm = CookieManager.getInstance()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { cm.removeAllCookies(null); cm.flush() }
        else cm.removeAllCookie()
    }

    private fun injectCookies(s: String) {
        val cm = CookieManager.getInstance()
        val domains = listOf(".qq.com", ".connect.qq.com", ".openmobile.qq.com")
        s.split(";").map { it.trim() }.filter { it.isNotBlank() }.forEach { cookie ->
            domains.forEach { d ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                    cm.setCookie("https:$d", "$cookie; domain=$d")
                else cm.setCookie("https:$d", cookie)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) cm.flush()
    }

    private fun launchGame() {
        log("🎯 启动 $GAME_PACKAGE")
        try {
            val i = packageManager.getLaunchIntentForPackage(GAME_PACKAGE)
            if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); log("✅ 已上号") }
            else log("⚠️ 游戏未安装")
        } catch (e: Exception) { log("❌ $e") }
    }

    // ============================================================
    // UI
    // ============================================================
    private fun showLocalIp() {
        try {
            val net = java.net.NetworkInterface.getNetworkInterfaces()
            while (net.hasMoreElements()) {
                val intf = net.nextElement()
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is java.net.Inet4Address) {
                        val ip = addr.hostAddress
                        if (!ip.startsWith("127.")) {
                            val text = "http://$ip:$HTTP_PORT/inject"
                            handler.post { ipText.text = text }
                            log("📡 $text")
                            setStatus("等待 token...")
                            return
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        handler.post { ipText.text = "http://(IP):$HTTP_PORT/inject" }
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        handler.post {
            try {
                logPanel.append("[${SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())}] $msg\n")
                val s = logPanel.layout?.getLineTop(logPanel.lineCount) ?: 0
                if (s > logPanel.height) logPanel.scrollTo(0, s - logPanel.height)
            } catch (_: Exception) {}
        }
    }

    private fun setStatus(t: String) { handler.post { statusText.text = t } }

    data class Token(
        val access_token: String?, val expires_in: String?,
        val openid: String?, val pay_token: String?,
        val pf: String?, val pfkey: String?
    )

    companion object {
        private const val HTTP_PORT = 8888
        private const val GAME_PACKAGE = "com.tencent.tmgp.sgame"
        private const val APP_ID = "716027609"
        private const val THIRD_APP_ID = "1104466820"
        private val TAG = "QQSwitcher"
    }
}
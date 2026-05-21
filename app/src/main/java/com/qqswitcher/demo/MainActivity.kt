package com.qqswitcher.demo

import android.content.Intent
import android.graphics.Bitmap
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.*
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList

/**
 * QQ 上号器 Demo
 * 
 * 核心流程：
 * 1. 从中控 API 拉取账号 Token 列表
 * 2. 选择账号 → 创建隐藏 WebView → 加载 QQ OAuth 回调 URL
 * 3. 注入 Cookie → 完成认证 → 拉起游戏
 */
class MainActivity : AppCompatActivity() {

    // ============================================================
    // Configuration - 部署时修改这里
    // ============================================================
    companion object {
        // 中控服务器地址（用于拉取 token 列表）
        private const val CONTROL_PANEL_URL = "http://your-server:8080/api/tokens"
        // 游戏包名（按需修改）
        private const val GAME_PACKAGE = "com.tencent.tmgp.sgame"  // 王者荣耀
        // 游戏登录协议 scheme
        private val GAME_SCHEMES = listOf(
            "tencent",           // 腾讯通用
            "qqmusic",           // QQ音乐
            "com.tencent.tmgp",  // 手游通用
        )
    }

    // ============================================================
    // Views
    // ============================================================
    private lateinit var accountList: RecyclerView
    private lateinit var logPanel: TextView
    private lateinit var statusText: TextView
    private lateinit var statusDot: View

    // ============================================================
    // State
    // ============================================================
    private val accounts = ArrayList<QQAccount>()
    private val adapter = AccountAdapter(accounts) { account -> onAccountClick(account) }
    private val handler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var currentAccount: QQAccount? = null

    // ============================================================
    // Lifecycle
    // ============================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        initWebView()
        log("🦞 QQ 上号器已启动")
        log("📱 SDK: ${Build.VERSION.SDK_INT}, 设备: ${Build.MODEL}")
        log("ℹ️ 正在拉取账号列表...")
        fetchAccounts()
    }

    override fun onDestroy() {
        // 清理 WebView 防止内存泄漏
        webView?.destroy()
        super.onDestroy()
    }

    // ============================================================
    // Initialization
    // ============================================================
    private fun initViews() {
        accountList = findViewById(R.id.accountList)
        logPanel = findViewById(R.id.logPanel)
        statusText = findViewById(R.id.statusText)
        statusDot = findViewById(R.id.statusDot)

        accountList.layoutManager = LinearLayoutManager(this)
        accountList.adapter = adapter

        // 测试用 - 硬编码一个示例账号以便快速调试
        adapter.addTestAccount(
            QQAccount(
                uin = "3111688136",
                nick = "测试号（ptsigx）",
                ptsigx = "c7ca116c6954de2888620eeeadadf7afd321a9e83aca88848c24a93c189376ebe144f76016118ba37c4315b3e1e29651f22184ba5f233ec35dd98f04b4de61eb8c4beb57b13816bcda8e20a48b451a875f8bf250c17d0ba7283310a98c4ec74e",
                authUrl = "http://ptlogin4.openmobile.qq.com/check_sig?" +
                        "pttype=1" +
                        "&uin=3111688136" +
                        "&service=ptqrlogin" +
                        "&nodirect=0" +
                        "&ptsigx=c7ca116c6954de2888620eeeadadf7afd321a9e83aca88848c24a93c189376ebe144f76016118ba37c4315b3e1e29651f22184ba5f233ec35dd98f04b4de61eb8c4beb57b13816bcda8e20a48b451a875f8bf250c17d0ba7283310a98c4ec74e" +
                        "&s_url=http%3A%2F%2Fconnect.qq.com" +
                        "&f_url=" +
                        "&ptlang=2052" +
                        "&ptredirect=100" +
                        "&aid=716027609" +
                        "&daid=381" +
                        "&j_later=0" +
                        "&low_login_hour=0" +
                        "&regmaster=0" +
                        "&pt_login_type=3" +
                        "&pt_aid=0" +
                        "&pt_aaid=16" +
                        "&pt_light=0" +
                        "&pt_3rd_aid=1104466820"
            )
        )
    }

    /**
     * 初始化隐藏 WebView，专用于 OAuth 认证流程
     * 隐藏是因为用户不需要看到页面跳转，后台完成 Cookie 注入即可
     */
    private fun initWebView() {
        val wv = WebView(this)
        wv.apply {
            layoutParams = ViewGroup.LayoutParams(1, 1) // 隐藏：1x1 像素
            isVisible = false  // 完全不可见

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
                // 允许文件访问 Cookie 同步
                allowFileAccess = false
                // QQ 登录需要这些
                mediaPlaybackRequiresUserGesture = false
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    log("🌐 加载: ${url?.take(120)}...")
                    setStatus("正在认证...")
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    log("✅ 页面加载完成: ${url?.take(80)}...")

                    // 打印当前 Cookie 用于调试
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        CookieManager.getInstance().flush()
                    }
                    dumpCookies()

                    // 检查是否已到 connect.qq.com（认证成功标志）
                    if (url?.contains("connect.qq.com") == true) {
                        log("🎉 认证成功！尝试拉起游戏...")
                        launchGame()
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url?.toString() ?: return false
                    log("🔀 重定向: ${url.take(100)}...")

                    // 检测游戏协议 scheme
                    GAME_SCHEMES.forEach { scheme ->
                        if (url.startsWith(scheme)) {
                            log("🎮 检测到游戏协议: $url")
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivity(intent)
                                log("✅ 已拉起游戏")
                                return true
                            } catch (e: Exception) {
                                log("❌ 拉起游戏失败: ${e.message}")
                                // 如果 Intent scheme 失败，试试直接启动包名
                                launchGame()
                                return true
                            }
                        }
                    }

                    // QQ 域名的认证跳转，让 WebView 继续加载
                    if (url.contains("qq.com") || url.contains("tencent.com")) {
                        return false
                    }

                    return false
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    val code = error?.errorCode ?: -1
                    val desc = error?.description?.toString() ?: "未知"
                    log("⚠️ WebView 错误: code=$code, desc=$desc")
                }

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?
                ) {
                    // QQ 某些环境用自签证书，需要放行
                    log("⚠️ SSL 错误: ${error?.toString()?.take(80)}，放行")
                    handler?.proceed()
                }
            }

            // 监听 Cookie 变化
            CookieManager.getInstance().setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
            }

            // 添加到根视图（隐藏状态）
            (window.decorView as ViewGroup).addView(this)
        }
        webView = wv
        log("🔧 WebView 初始化完成（隐藏模式）")
    }

    // ============================================================
    // Account Management
    // ============================================================

    /**
     * 从中控 API 拉取账号列表
     */
    private fun fetchAccounts() {
        setStatus("拉取账号列表...")
        val request = Request.Builder()
            .url(CONTROL_PANEL_URL)
            .get()
            .build()

        OkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                log("⚠️ 拉取失败: ${e.message}")
                log("→ 使用本地测试账号继续")
                setStatus("离线模式（测试账号）")
                // 失败不处理，示例账号已在 initViews 中添加
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    try {
                        val result = Gson().fromJson(body, TokenResponse::class.java)
                        handler.post {
                            accounts.clear()
                            result.data?.forEach { token ->
                                accounts.add(QQAccount(
                                    uin = token.uin,
                                    nick = token.nick ?: token.uin,
                                    ptsigx = token.ptsigx,
                                    authUrl = buildAuthUrl(token)
                                ))
                            }
                            adapter.notifyDataSetChanged()
                            log("✅ 成功拉取 ${accounts.size} 个账号")
                            setStatus("就绪 · ${accounts.size} 个账号")
                        }
                    } catch (e: Exception) {
                        log("⚠️ 解析失败: ${e.message}")
                    }
                }
            }
        })
    }

    /**
     * 点击账号 → 执行上号流程
     */
    private fun onAccountClick(account: QQAccount) {
        if (account.uin == currentAccount?.uin) {
            log("⏭️ 已是当前账号: ${account.uin}")
            return
        }

        log("")
        log("═══════════════════════════════════")
        log("🔄 切换账号: ${account.uin} (${account.nick})")
        log("═══════════════════════════════════")
        currentAccount = account

        // Step 1: 清除旧 Cookie
        clearQqCookies()

        // Step 2: 注入自定义 Cookie（如果有）
        if (!account.cookieStr.isNullOrBlank()) {
            injectCookies(account.cookieStr!!)
        }

        // Step 3: 加载回调 URL
        handler.postDelayed({
            loadAuthUrl(account)
        }, 500) // 给 Cookie 清除一点时间
    }

    /**
     * 加载 QQ OAuth 回调 URL
     */
    private fun loadAuthUrl(account: QQAccount) {
        val wv = webView ?: return
        val url = account.authUrl

        if (url.isBlank()) {
            log("❌ 认证 URL 为空")
            return
        }

        log("🚀 加载认证 URL...")
        log("URL: ${url.take(100)}...")
        setStatus("认证中 (${account.uin})")

        wv.post {
            wv.loadUrl(url)
        }

        // 超时保护 - 15秒后如果还没成功，打印诊断信息
        handler.postDelayed({
            if (wv.url?.contains("connect.qq.com") != true) {
                log("⏱️ 认证超时（15s），当前 URL: ${wv.url?.take(80)}")
                log("📋 请检查 ptsigx 是否过期")
                setStatus("认证超时")
            }
        }, 15000)
    }

    // ============================================================
    // Cookie Management
    // ============================================================

    /**
     * 清除 QQ 域名的所有 Cookie
     */
    private fun clearQqCookies() {
        log("🧹 清除旧 Cookie...")
        val cm = CookieManager.getInstance()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cm.removeAllCookies(null)
            cm.flush()
        } else {
            cm.removeAllCookie()
        }

        // 额外清除 WebView 缓存
        webView?.clearCache(true)
        webView?.clearHistory()
        webView?.clearFormData()

        log("✅ Cookie 已清除")
    }

    /**
     * 手动注入 Cookie（当有 skey/p_skey 时使用）
     */
    private fun injectCookies(cookieStr: String) {
        val cm = CookieManager.getInstance()
        val domains = listOf(
            ".qq.com",
            ".connect.qq.com",
            ".openmobile.qq.com",
            "qq.com",
            "connect.qq.com"
        )

        // 按分号分割多个 Cookie
        val cookies = cookieStr.split(";")
        cookies.forEach { cookie ->
            val trimmed = cookie.trim()
            if (trimmed.isNotBlank()) {
                domains.forEach { domain ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        cm.setCookie(domain, "$trimmed; domain=$domain")
                    } else {
                        cm.setCookie(domain, trimmed)
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cm.flush()
        }

        log("✅ 已注入 ${cookies.size} 个 Cookie")
    }

    /**
     * 打印当前所有 QQ 域名的 Cookie（调试用）
     */
    private fun dumpCookies() {
        val cm = CookieManager.getInstance()
        val debugDomains = listOf(
            "https://qq.com",
            "https://connect.qq.com",
            "https://openmobile.qq.com",
            "https://ptlogin4.qq.com"
        )

        debugDomains.forEach { domain ->
            val cookie = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cm.getCookie(domain)
            } else {
                cm.getCookie(domain)
            }
            if (!cookie.isNullOrBlank()) {
                log("🍪 Cookie[$domain]: ${cookie.take(100)}...")
            }
        }
    }

    // ============================================================
    // Game Launch
    // ============================================================

    /**
     * 尝试拉起游戏
     */
    private fun launchGame() {
        log("🎯 尝试启动游戏...")

        // 方式 1: 尝试通过包名启动
        try {
            val intent = packageManager.getLaunchIntentForPackage(GAME_PACKAGE)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                log("✅ 成功启动游戏: $GAME_PACKAGE")
                setStatus("✅ 已上号 ${currentAccount?.uin}")
                return
            }
        } catch (e: Exception) {
            log("⚠️ 包名启动失败: ${e.message}")
        }

        // 方式 2: 尝试打开游戏商店页（如果没安装）
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("market://details?id=$GAME_PACKAGE")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            log("📦 游戏未安装，打开应用商店")
        } catch (e: Exception) {
            log("⚠️ 商店打开失败: ${e.message}")
        }
    }

    // ============================================================
    // Utilities
    // ============================================================

    private fun log(msg: String) {
        Log.d(TAG, msg)
        handler.post {
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
            val time = sdf.format(Date())
            logPanel.append("[$time] $msg\n")
            // 自动滚动到底部
            val scrollAmount = logPanel.layout?.getLineTop(logPanel.lineCount) ?: 0
            if (scrollAmount > logPanel.height) {
                logPanel.scrollTo(0, scrollAmount - logPanel.height)
            }
        }
    }

    private fun setStatus(text: String) {
        handler.post {
            statusText.text = text
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 如果游戏返回，检查是否成功
        val data = intent.data?.toString() ?: ""
        if (data.contains("qq.com") || data.contains("tencent")) {
            log("🔙 从游戏返回，URL: ${data.take(100)}")
        }
    }

    // ============================================================
    // Data Models
    // ============================================================

    data class QQAccount(
        val uin: String,           // QQ号
        val nick: String?,         // 昵称
        val ptsigx: String? = null,// 临时签名（一次性）
        val authUrl: String = "",  // 完整的认证回调 URL
        val cookieStr: String? = null // 持久化 Cookie（skey/p_skey 等）
    )

    data class TokenResponse(
        val code: Int?,
        val msg: String?,
        val data: List<TokenItem>?
    )

    data class TokenItem(
        val uin: String,
        @SerializedName("nick") val nick: String?,
        @SerializedName("ptsigx") val ptsigx: String?,
        @SerializedName("cookie") val cookie: String?,
        @SerializedName("aid") val aid: String?,
        @SerializedName("daid") val daid: String?,
        @SerializedName("pt_3rd_aid") val pt3rdAid: String?
    )

    /**
     * 根据 TokenItem 构造完整认证 URL
     */
    private fun buildAuthUrl(token: TokenItem): String {
        // 如果用完整 URL 模板，可以通过拼接替代
        return "http://ptlogin4.openmobile.qq.com/check_sig?" +
                "pttype=1" +
                "&uin=${token.uin}" +
                "&service=ptqrlogin" +
                "&nodirect=0" +
                "&ptsigx=${token.ptsigx ?: ""}" +
                "&s_url=http%3A%2F%2Fconnect.qq.com" +
                "&f_url=" +
                "&ptlang=2052" +
                "&ptredirect=100" +
                "&aid=${token.aid ?: "716027609"}" +
                "&daid=${token.daid ?: "381"}" +
                "&j_later=0" +
                "&low_login_hour=0" +
                "&regmaster=0" +
                "&pt_login_type=3" +
                "&pt_aid=0" +
                "&pt_aaid=16" +
                "&pt_light=0" +
                "&pt_3rd_aid=${token.pt3rdAid ?: "1104466820"}"
    }

    private val TAG = "QQSwitcher"
}

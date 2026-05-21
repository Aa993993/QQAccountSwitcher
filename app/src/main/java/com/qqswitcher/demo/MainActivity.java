package com.qqswitcher.demo;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int HTTP_PORT = 8888;
    private static final String GAME_PACKAGE = "com.tencent.tmgp.sgame";
    private static final String APP_ID = "716027609";
    private static final String THIRD_APP_ID = "1104466820";

    private TextView logView;
    private TextView statusView;
    private final Handler handler = new Handler();
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_main);

            logView = findViewById(R.id.logPanel);
            statusView = findViewById(R.id.statusText);
            webView = findViewById(R.id.hiddenWebView);

            webView.getSettings().setJavaScriptEnabled(true);
            webView.getSettings().setDomStorageEnabled(true);
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    log("✅ WebView: " + url.substring(0, Math.min(60, url.length())));
                    if (url.contains("connect.qq.com")) {
                        log("🎉 认证成功!");
                        launchGame();
                    }
                }
            });
            CookieManager.getInstance().setAcceptCookie(true);

            // Start HTTP server in background
            new Thread(this::startHttpServer).start();

            // Show IP
            showLocalIp();

            log("🦞 启动完成");
            setStatus("等待推送 token...");
        } catch (Exception e) {
            Log.e("QQSwitcher", "启动失败", e);
            if (logView != null) {
                logView.setText("启动失败: " + e.getMessage());
            }
        }
    }

    private void startHttpServer() {
        try {
            ServerSocket ss = new ServerSocket(HTTP_PORT);
            log("🌐 HTTP 端口: " + HTTP_PORT);
            while (true) {
                try {
                    Socket c = ss.accept();
                    handleClient(c);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            log("❌ HTTP: " + e.getMessage());
        }
    }

    private void handleClient(Socket c) {
        new Thread(() -> {
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                String line = r.readLine();
                if (line == null) return;
                String[] parts = line.split(" ");
                if (parts.length < 2) return;

                int contentLength = 0;
                String h;
                while ((h = r.readLine()) != null && !h.isBlank()) {
                    if (h.toLowerCase().startsWith("content-length:")) {
                        contentLength = Integer.parseInt(h.split(":")[1].trim());
                    }
                }

                String body = "";
                if (contentLength > 0) {
                    char[] buf = new char[contentLength];
                    r.read(buf, 0, contentLength);
                    body = new String(buf);
                }

                String code, msg;
                if ("POST".equals(parts[0]) && "/inject".equals(parts[1])) {
                    log("📥 收到: " + body.substring(0, Math.min(80, body.length())));
                    String result = processToken(body);
                    code = "200 OK";
                    msg = "{\"code\":0,\"msg\":\"" + result + "\"}";
                } else {
                    code = "200 OK";
                    msg = "{\"status\":\"ok\"}";
                }

                String resp = "HTTP/1.1 " + code + "\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: " + msg.getBytes("UTF-8").length + "\r\n" +
                        "Connection: close\r\nAccess-Control-Allow-Origin: *\r\n\r\n" + msg;

                OutputStreamWriter w = new OutputStreamWriter(c.getOutputStream(), "UTF-8");
                w.write(resp);
                w.flush();
            } catch (Exception ignored) {}
            finally { try { c.close(); } catch (Exception ignored) {} }
        }).start();
    }

    private String processToken(String body) {
        try {
            Token t = new Gson().fromJson(body, Token.class);
            if (t.access_token == null || t.access_token.isEmpty()) return "缺少 access_token";
            log("👤 openid: " + t.openid.substring(0, Math.min(10, t.openid.length())));
            log("🔑 token: " + t.access_token.substring(0, Math.min(16, t.access_token.length())));

            // Root injection
            if (injectByRoot(t)) {
                runOnUiThread(this::launchGame);
                return "Root 注入成功";
            }

            // WebView fallback
            log("⚠️ Root 失败，尝试 WebView");
            clearCookies();
            String url = "https://graph.qq.com/oauth2.0/login?access_token=" + t.access_token +
                    "&openid=" + t.openid + "&ret=0&s_url=https://connect.qq.com";
            runOnUiThread(() -> webView.loadUrl(url));
            return "WebView 认证中...";

        } catch (Exception e) {
            clearCookies();
            injectCookies(body);
            runOnUiThread(this::launchGame);
            return "Cookie 注入";
        }
    }

    private boolean injectByRoot(Token t) {
        String xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n" +
                "    <string name=\"access_token\">" + t.access_token + "</string>\n" +
                "    <string name=\"expires_in\">" + (t.expires_in != null ? t.expires_in : "5184000") + "</string>\n" +
                "    <string name=\"openid\">" + t.openid + "</string>\n" +
                "    <string name=\"pay_token\">" + (t.pay_token != null ? t.pay_token : "") + "</string>\n" +
                "    <string name=\"pf\">" + (t.pf != null ? t.pf : "openmobile_android") + "</string>\n" +
                "    <string name=\"pfkey\">" + (t.pfkey != null ? t.pfkey : "") + "</string>\n" +
                "    <long name=\"expires_at\" value=\"" + (System.currentTimeMillis()/1000 + 5184000) + "\" />\n" +
                "    <int name=\"ret\" value=\"0\" />\n</map>";

        String[] paths = {
            "/data/data/" + GAME_PACKAGE + "/shared_prefs/" + APP_ID + "_" + t.openid + "_" + APP_ID + ".xml",
            "/data/data/" + GAME_PACKAGE + "/shared_prefs/" + THIRD_APP_ID + "_" + t.openid + "_" + THIRD_APP_ID + ".xml",
            "/data/data/" + GAME_PACKAGE + "/shared_prefs/tencent_auth.xml",
        };

        for (String path : paths) {
            try {
                String dir = path.substring(0, path.lastIndexOf("/"));
                runRoot("mkdir -p " + dir);
                runRoot("chmod 777 " + dir);
                runRoot("echo '" + xml + "' > '" + path + "'");
                runRoot("chmod 666 " + path);
                log("📝 写入: " + path.substring(path.lastIndexOf("/") + 1));
            } catch (Exception ignored) {}
        }

        String result = runRoot("ls /data/data/" + GAME_PACKAGE + "/shared_prefs/ 2>/dev/null");
        return result.contains("tencent_auth") || result.contains(APP_ID);
    }

    private String runRoot(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            os.writeBytes(cmd + "\n");
            os.writeBytes("exit\n");
            os.flush();
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = reader.readLine()) != null) sb.append(l).append("\n");
            p.waitFor();
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void clearCookies() {
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
    }

    private void injectCookies(String s) {
        String[] domains = {".qq.com", ".connect.qq.com", ".openmobile.qq.com"};
        CookieManager cm = CookieManager.getInstance();
        for (String cookie : s.split(";")) {
            cookie = cookie.trim();
            if (cookie.isEmpty()) continue;
            for (String d : domains) {
                cm.setCookie("https:" + d, cookie + "; domain=" + d);
            }
        }
        cm.flush();
    }

    private void launchGame() {
        log("🎯 启动 " + GAME_PACKAGE);
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(GAME_PACKAGE);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                log("✅ 已上号");
            } else {
                log("⚠️ 游戏未安装");
            }
        } catch (Exception e) {
            log("❌ " + e.getMessage());
        }
    }

    private void showLocalIp() {
        try {
            java.util.Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            while (nets.hasMoreElements()) {
                NetworkInterface ni = nets.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (addr instanceof java.net.Inet4Address) {
                        String ip = addr.getHostAddress();
                        if (!ip.startsWith("127.")) {
                            String text = "http://" + ip + ":" + HTTP_PORT + "/inject";
                            runOnUiThread(() -> {
                                TextView ipText = findViewById(R.id.ipText);
                                if (ipText != null) ipText.setText(text);
                            });
                            log("📡 " + text);
                            return;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void log(String msg) {
        Log.d("QQSwitcher", msg);
        handler.post(() -> {
            try {
                String time = new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date());
                logView.append("[" + time + "] " + msg + "\n");
                int scroll = logView.getLayout() != null ?
                    logView.getLayout().getLineTop(logView.getLineCount()) - logView.getHeight() : 0;
                if (scroll > 0) logView.scrollTo(0, scroll);
            } catch (Exception ignored) {}
        });
    }

    private void setStatus(String t) {
        handler.post(() -> statusView.setText(t));
    }

    static class Token {
        String access_token;
        String expires_in;
        String openid;
        String pay_token;
        String pf;
        String pfkey;
    }
}

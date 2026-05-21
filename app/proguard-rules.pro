# QQ 上号器 ProGuard 规则
# 保留 WebView JS 接口
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# 保留 Gson 使用的数据类
-keep class com.qqswitcher.demo.MainActivity$TokenResponse { *; }
-keep class com.qqswitcher.demo.MainActivity$TokenItem { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

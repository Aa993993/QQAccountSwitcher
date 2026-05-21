# QQ 上号器 Demo 🦞

适用于代练工作室的**批量 QQ 账号 → 手游登录**原型验证项目。

## 原理

```
点击账号 → 隐藏 WebView 加载 QQ OAuth 回调 URL
         → Cookie 注入（skey/p_skey/pt4_token）
         → 重定向到 connect.qq.com（认证成功标志）
         → 拉起游戏 App（Intent / 包名）
```

## 项目结构

```
qq-account-switcher/
├── app/
│   └── src/main/
│       ├── java/com/qqswitcher/demo/
│       │   ├── MainActivity.kt      ← 核心逻辑
│       │   └── AccountAdapter.kt    ← 账号列表适配器
│       ├── res/layout/
│       │   └── activity_main.xml    ← 主界面
│       ├── res/xml/
│       │   └── network_security_config.xml
│       └── AndroidManifest.xml
├── build.gradle.kts
└── settings.gradle
```

## 快速开始

### 1. 导入 Android Studio

- File → Open → 选择 `qq-account-switcher` 目录
- 等待 Gradle Sync 完成
- 连接 Android 设备 / 启动模拟器
- 点 Run

### 2. 修改配置

打开 `MainActivity.kt`，修改顶部常量：

```kotlin
companion object {
    // 中控服务器地址
    private const val CONTROL_PANEL_URL = "http://your-server:8080/api/tokens"
    // 目标游戏包名
    private const val GAME_PACKAGE = "com.tencent.tmgp.sgame"  // 王者荣耀
}
```

### 3. 测试账号

App 里已经硬编码了一个示例账号（你给的 `ptsigx`），安装后直接点那个账号就能跑通认证流程。

## 中控 API 格式

如果你搭了后端，API 返回格式应该是：

```json
{
  "code": 0,
  "msg": "success",
  "data": [
    {
      "uin": "3111688136",
      "nick": "昵称",
      "ptsigx": "c7ca116c...",
      "cookie": "skey=xxx; p_skey=yyy; pt4_token=zzz",
      "aid": "716027609",
      "daid": "381",
      "pt_3rd_aid": "1104466820"
    }
  ]
}
```

其中：
- `ptsigx` — 一次性签名（有效期 5-10 分钟），用于首次认证
- `cookie` — 持久化会话，有效期几小时~几天，注入后可直接登录

## 调试日志

App 底部有一个黑色日志面板，会实时显示：
- Cookie 注入状态
- 页面加载/重定向过程
- 认证成功/失败
- 游戏拉起结果

你也可以通过 `adb logcat -s QQSwitcher` 查看。

## 下一步

1. ✅ 验证回调 URL → 游戏登录能否走通
2. ➡️ 搭建中控后端（Token 存储/刷新/分配）
3. ➡️ 接入更多游戏
4. ➡️ 封装成多开/群控方案
